package jp.keepia.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repositoryの検索処理を実際のデータベースに接続して検証する
 * 結合テストクラスです。
 * <p>
 * テスト専用のPostgreSQLを使用し、利用者及び顧客の所属範囲に応じた検索と、
 * ログイン失敗管理情報の取得を検証します。
 * <p>
 * 各テストはトランザクション内で実行し、
 * テスト終了後に変更内容をロールバックします。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepositoryIntegrationTest {
	
	/** 以下、テストに必要な静的定数フィールドです。 */
	private static final UUID TENANT_ID =
			UUID.fromString("11000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_TENANT_ID =
			UUID.fromString("11000000-0000-0000-0000-000000000002");
    private static final UUID CUSTOMER_ID =
            UUID.fromString("12000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("13000000-0000-0000-0000-000000000001");
    private static final String EMAIL = "repository-admin@example.com";
    private static final String KEY_HASH = "b".repeat(64);
    
    /** テストデータの登録及び削除に使用するJDBC操作クラスです */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /** 以下、テスト用のRepositoryです */
    @Autowired
    private AppUserRepository appUserRepository;
    
    @Autowired
    private CustomerRepository customerRepository;
    
    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;
    
    /**
     * 各テストの実行前に検証用データを準備します。
     * <p>
     * 既存の対象テーブルデータを削除した後、
     * 保守会社、顧客、管理者およびログイン失敗管理情報を登録します。
     */
    @BeforeEach
    void setUp() {
    	jdbcTemplate.update("delete from login_rate_limits");
    	jdbcTemplate.update("delete from app_users");
    	jdbcTemplate.update("delete from customers");
    	jdbcTemplate.update("delete from tenants");
    	
    	jdbcTemplate.update(
    			"insert into tenants (id, name, status) values (?, ?, ?)",
    			TENANT_ID,
    			"Repository Test Tenant",
    			"ACTIVE");
    	
    	jdbcTemplate.update(
    			"""
    			insert into customers (
    				id,
    				tenant_id,
    				name,
    				contact_email,
    				status
    				) values (?, ?, ?, ?, ?)
    			""",
    			CUSTOMER_ID,
    			TENANT_ID,
    			"Repository Test Customer",
    			"customer@example.com",
    			"ACTIVE");
    	
    	jdbcTemplate.update(
    			"""
    			insert into app_users (
    				id,
    				tenant_id,
    				customer_id,
    				email,
    				display_name,
    				role,
    				status,
    				password_hash
    			) values (?, ?, null, ?, ?, ?, ?, ?)
    			""",
    			USER_ID,
    			TENANT_ID,
    			EMAIL,
    			"Repository Test Admin",
    			"ADMIN",
    			"ACTIVE",
    			"$2a$10$012345678901234567890u012345678901234567890123456789012");
    	
    	loginRateLimitRepository.insertIfAbsent(
    			UUID.randomUUID(),
    			KEY_HASH,
    			Instant.parse("2026-10-29T00:00:00Z"));
    }
    
    /**
     * 正規化済みのメールアドレスを指定して、
     * 対象の利用者を検索できることを検証します。
     * <p>
     * 検索結果に利用者が存在し、
     * 取得した利用者IDが期待値と一致することを確認します。
     */
    @Test
    @DisplayName("正規化済みメールアドレスで利用者を検索できる")
    void findsUserByNormalizedEmail() {
    	assertThat(appUserRepository.findByEmail(EMAIL))
    			.isPresent()
    			.get()
    			.extracting(user -> user.getId())
    			.isEqualTo(USER_ID);
    }
    
    /**
     * 存在しないメールアドレスを指定した場合に、
     * 空のOptionalが返されることを検証します。
     */
    @Test
    @DisplayName("存在しないメールアドレスでは空のOptionalを返す")
    void missingEmailReturnsEmptyOptional() {
    	assertThat(appUserRepository.findByEmail("missing@example.com")).isEmpty();
    }
    
    /**
     * 利用者IDと保守会社IDの両方が一致する場合に、
     * 対象の利用者を取得できることを検証します。
     */
    @Test
    @DisplayName("利用者IDと保守会社IDの両方が一致する利用者を検索できる")
    void findsUserWithinTenantScope() {
    	assertThat(appUserRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).isPresent();
    }
    
    /**
     * 利用者IDが一致していても保守会社IDが異なる場合に、
     * 利用者を取得できないことを検証します。
     * <p>
     * 異なる保守会社に属する利用者情報が、
     * 検索結果に含まれないことを確認します。
     */
    @Test
    @DisplayName("保守会社IDが異なる場合は利用者を取得できない")
    void rejectsUserOutsideTenantScope() {
    	assertThat(appUserRepository.findByIdAndTenantId(USER_ID, OTHER_TENANT_ID)).isEmpty();
    }
    
    /**
     * 顧客IDと保守会社IDを指定した結果で、
     * 所属範囲に応じた結果が返されることを検証します。
     * <p>
     * 正しい保守会社IDでは顧客を取得でき、
     * 異なる保守会社IDでは取得できないことを確認します。
     */
    @Test
    @DisplayName("顧客IDと保守会社IDの両方が一致する顧客を検索できる")
    void findsCustomerWithinTenantScope() {
    	assertThat(customerRepository.findByIdAndTenantId(CUSTOMER_ID, TENANT_ID)).isPresent();
    	assertThat(customerRepository.findByIdAndTenantId(CUSTOMER_ID, OTHER_TENANT_ID)).isEmpty();
    }
    
    /**
     * HMACキーのハッシュ値を指定して、
     * ログイン失敗管理情報を検索できることを検証します。
     * <p>
     * 検索結果のハッシュ値が一致し、
     * 初期状態のログイン失敗回数が0であることを確認します。
     */
    @Test
    @DisplayName("HMACキーでログイン失敗管理行を検索できる")
    void findsLoginRateLimitByKeyHash() {
    	assertThat(loginRateLimitRepository.findByKeyHash(KEY_HASH))
    			.isPresent()
    			.get()
    			.satisfies(rate -> {
    				assertThat(rate.getKeyHash()).isEqualTo(KEY_HASH);
    				assertThat(rate.getFailureCount()).isZero();
    			});
    }
}
