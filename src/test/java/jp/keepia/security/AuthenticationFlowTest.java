package jp.keepia.security;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Spring Securityの認証・認可処理を、
 * {@link MockMvc} を使用して検証する結合テストクラスです。
 * <p>
 * ログイン、ログアウト、認証セッション、CSRF対策及び
 * ロールによるアクセス制御の動作を検証します。
 * <p>
 * ログイン失敗回数の上限によるアクセス制限や、
 * ログイン成功時の失敗回数の初期化についても確認します。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthenticationFlowTest.RoleRestrictedController.class)
class AuthenticationFlowTest {
	
	/** テストで使用する定数フィールドです。 */
	private static final UUID TENANT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String EMAIL ="admin@example.com";
    private static final String PASSWORD = "test-password";
    private static final String SOURCE_ADDRESS = "192.0.2.20";
    private static final int MAX_FAILURES = 5;
    
    /** HTTPリクエストを送信し、認証・認可の動作を検証するために使用します。 */
    @Autowired
    private MockMvc mockMvc;
    
    /** テストデータの初期化とDBへの直接問い合わせに使用します。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /** テストユーザーのパスワードをハッシュ化するために使用します。 */
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    /**
     * 各テスト実行前に認証用のテストデータを初期化します。
     * <p>
     * 関連テーブルのレコードを削除した後、
     * テスト用のテナントと管理者ユーザーを登録します。
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
    			"Test Tenant",
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
    			"Test Admin",
    			"ADMIN",
    			"ACTIVE",
    			passwordEncoder.encode(PASSWORD));
    }
    
    /**
     * 未認証状態でもログイン画面を表示できることを検証します。
     * <p>
     * ログイン画面へのGETリクエストが成功し、
     * 未認証状態のままログイン用ビューが返されることを確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("未認証でもログイン画面を表示できる")
    void loginPageIsPublic() throws Exception {
    	mockMvc.perform(get("/login"))
    			.andExpect(status().isOk())
    			.andExpect(view().name("login"))
    			.andExpect(unauthenticated());
    }
    
    /**
     * 未認証状態で保護されたページにアクセスした場合に、
     * ログイン画面へリダイレクトされることを検証します。
     * <p>
     * ホーム画面へのアクセス時にリダイレクトが発生し、
     * 未認証状態が維持されることを確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("未認証でホームへアクセスするとログイン画面へ移動する")
    void protectedPageRedirectsToLogin() throws Exception {
    	mockMvc.perform(get("/"))
    			.andExpect(status().is3xxRedirection())
    			.andExpect(redirectedUrl("/login"))
    			.andExpect(unauthenticated());
    }
    
    /**
     * 正しいメールアドレスとパスワードによって、
     * ログインに成功することを検証します。
     * <p>
     * CSRFトークンを含むログイン要求を送信し、
     * ホーム画面へのリダイレクトと対象ユーザーの認証状態を確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("正しいメールとパスワードでログインできる")
    void validCredentialsAuthenticateUser() throws Exception {
    	mockMvc.perform(post("/login")
    					 .servletPath("/login")
    			         .with(csrf())
    			         .with(remoteAddress(SOURCE_ADDRESS))
    			         .param("email", EMAIL)
    			         .param("password", PASSWORD))
			    .andExpect(status().is3xxRedirection())
			    .andExpect(redirectedUrl("/"))
			    .andExpect(authenticated().withUsername(EMAIL));
    }
    
    /**
     * 誤ったパスワードによるログインが拒否されることを検証します。
     * <p>
     * 登録済みのメールアドレスと不正なパスワードでログインを試行し、
     * ログイン画面へのリダイレクトと未認証状態を確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("誤ったパスワードでは認証できない")
    void invalidPasswordDoesNotAuthenticateUser() throws Exception {
    	mockMvc.perform(post("/login")
    					.servletPath("/login")
    					.with(csrf())
    					.with(remoteAddress(SOURCE_ADDRESS))
    					.param("email", EMAIL)
    					.param("password", "wrong-password"))
    			.andExpect(status().is3xxRedirection())
    			.andExpect(redirectedUrl("/login?error"))
    			.andExpect(unauthenticated());
    }
    
    /**
     * CSRFトークンを含まないログイン要求が拒否されることを検証します。
     * <p>
     * 正しい認証情報を送信した場合でも、
     * CSRFトークンが存在しなければ403エラーとなり、
     * 未認証状態が維持されることを検証します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("CSRFトークンなしのログイン要求を拒否する")
    void loginWithoutCsrfIsRejected() throws Exception {
    	MvcResult loginPage = mockMvc.perform(get("/login"))
    				.andExpect(status().isOk())
    				.andReturn();
    	
    	MockHttpSession session = 
    			(MockHttpSession) loginPage.getRequest().getSession(false);
    	
    	assertThat(session).isNotNull();
    	
    	mockMvc.perform(post("/login").servletPath("/login")
    					.session(session)
    					.param("email", EMAIL)
    					.param("password", PASSWORD))
    			.andExpect(status().isForbidden())
    			.andExpect(unauthenticated());
    }
    
    /**
     * ログイン後のセッションを使用して、
     * ホーム画面にアクセスできることを検証します。
     * <p>
     * 認証成功時のセッションを後続のリクエストで再利用し、
     * ホーム画面が表示され、認証状態が維持されることを確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("ログイン後は同じセッションでホーム画面を表示できる")
    void authenticatedSessionCanOpenHome() throws Exception {
    	MockHttpSession session = login(SOURCE_ADDRESS);
    	
    	mockMvc.perform(get("/").session(session))
    			.andExpect(status().isOk())
    			.andExpect(view().name("home"))
    			.andExpect(authenticated().withUsername(EMAIL));
    }
    
    /**
     * 正常なログアウト要求によって、
     * 認証セッションが無効化されることを検証します。
     * <p>
     * CSRFトークン付きのログアウト要求を送信し、
     * ログイン画面へのリダイレクトとセッションの無効化を確認します。
     * また、新しい未認証リクエストがログイン画面へ誘導されることも確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("CSRFトークン付きのPOST logoutでセッションを無効化する")
    void logoutInvalidatesSession() throws Exception {
    	MockHttpSession session = login(SOURCE_ADDRESS);
    	
    	mockMvc.perform(post("/logout")
    			        .session(session)
    			        .with(csrf()))
    			.andExpect(status().is3xxRedirection())
    			.andExpect(redirectedUrl("/login?logout"))
    			.andExpect(unauthenticated());
    	
    	assertThat(session.isInvalid()).isTrue();
    	mockMvc.perform(get("/"))
    			.andExpect(status().is3xxRedirection())
    			.andExpect(redirectedUrl("/login"))
    			.andExpect(unauthenticated());
    }
    
    /**
     * CSRFトークンを含まないログアウト要求が拒否されることを検証します。
     * <p>
     * 認証済みセッションから不正なログアウト要求を送信し、
     * 403エラーが返され、認証状態とセッションが維持されることを確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("CSRFトークン無しのPOST logoutを拒否する")
    void logoutWithoutCsrfIsRejected() throws Exception {
    	MockHttpSession session = login(SOURCE_ADDRESS);
    	
    	mockMvc.perform(get("/").session(session))
    				.andExpect(status().isOk());
    	
    	mockMvc.perform(post("/logout").session(session))
    				.andExpect(status().isForbidden())
    				.andExpect(authenticated().withUsername(EMAIL));
    	
    	assertThat(session.isInvalid()).isFalse();
    }
    
    /**
     * 必要なロールを持たないユーザーのアクセスが
     * 拒否されることを検証します。
     * <p>
     * ADMINロールのユーザーでOPERATOR専用のURLへアクセスし、
     * 403エラーが返され、アクセス拒否画面へ転送されることを確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("権限不足のURLアクセスは403画面へ転送する")
    void insufficientRoleReturnsForbidden() throws Exception {
    	MockHttpSession session = login(SOURCE_ADDRESS);
    	
    	mockMvc.perform(get("/test/operator-only").session(session))
    			.andExpect(status().isForbidden())
    			.andExpect(forwardedUrl("/access-denied"));
    }
    
    /**
     * アクセス拒否画面に内部の実装情報が
     * 表示されないことを検証します。
     * <p>
     * 403エラー画面のレスポンスに、
     * 例外名や内部パッケージ名などの情報が
     * 含まれていないことを確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("403画面へ内部例外名やSQL情報を表示しない")
    void accessDeniedPageDoesNotExposeInternalDetails() throws Exception {
    	mockMvc.perform(get("/access-denied"))
    			.andExpect(status().isForbidden())
    			.andExpect(view().name("error/403"))
    			.andExpect(content().string(not(containsString("Exception"))))
    			.andExpect(content().string(not(containsString("SQLException"))))
    			.andExpect(content().string(not(containsString("jp.keepia"))));
    }
    
    /**
     * ログイン失敗回数が上限に達した場合に、
     * 正しいパスワードでもログインが拒否されることを検証します。
     * <p>
     * 同一のメールアドレスと接続元IPアドレスで失敗を繰り返し、
     * 上限到達後のログイン要求が拒否され、
     * 未認証状態になることを確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("失敗上限到達後は正しいパスワードでも照合前に拒否する")
    void blockedLoginRejectsCorrectPassword() throws Exception {
    	for (int index = 0; index < MAX_FAILURES; index++) {
    		performFailedLogin(SOURCE_ADDRESS);
    	}
    	
    	mockMvc.perform(post("/login")
    					.servletPath("/login")
    					.with(csrf())
    					.with(remoteAddress(SOURCE_ADDRESS))
    					.param("email", EMAIL)
    					.param("password", PASSWORD))
    			.andExpect(status().is3xxRedirection())
    			.andExpect(redirectedUrl("/login?error"))
    			.andExpect(unauthenticated());
    }
    
    /**
     * ログイン成功時に失敗回数が初期化されることを検証します。
     * <p>
     * 同一のメールアドレスと接続元IPアドレスで
     * ログイン失敗後に正常なログインを実行し、
     * DBに記録された失敗回数が0になることを確認します。
     * 
     * @throws Exception リクエストの実行または結果の検証中に例外が発生した場合です。
     */
    @Test
    @DisplayName("ログイン成功後に同じメールと接続元の失敗回数を初期化する")
    void successfulLoginResetsFailureCount() throws Exception {
    	performFailedLogin(SOURCE_ADDRESS);
    	
    	login(SOURCE_ADDRESS);
    	
    	Integer failureCount = jdbcTemplate.queryForObject(
    			"select failure_count from login_rate_limits",
    			Integer.class);
    	assertThat(failureCount).isZero();
    }
    
    /**
     * 指定された接続元IPアドレスからログインを実行します。
     * <p>
     * テストユーザーの認証が成功することを検証し、
     * 後続のリクエストで利用するセッションを取得します。
     *  
     * @param sourceAddress 接続元IPアドレスです。
     * @return 認証成功時に取得したセッションを返します。
     * @throws Exception リクエストの実行または検証中に例外が発生した場合です。
     */
    private MockHttpSession login(String sourceAddress) throws Exception {
    	MvcResult result = mockMvc.perform(post("/login")
    					.servletPath("/login")
    					.with(csrf())
    					.with(remoteAddress(sourceAddress))
    					.param("email", EMAIL)
    					.param("password", PASSWORD))
    			.andExpect(status().is3xxRedirection())
    			.andExpect(redirectedUrl("/"))
    			.andExpect(authenticated().withUsername(EMAIL))
    			.andReturn();
    			
    	return (MockHttpSession) result.getRequest().getSession(false);
    }
    
    /**
     * 指定された接続元IPアドレスからログイン失敗を発生させます。
     * <p>
     * テストユーザーのメールアドレスと誤ったパスワードでログインを試行し、
     * 認証が拒否されることを確認します。
     * 
     * @param sourceAddress 接続元IPアドレスです。
     * @throws Exception リクエストの実行または検証中に例外が発生した場合です。
     */
    private void performFailedLogin(String sourceAddress) throws Exception {
    	mockMvc.perform(post("/login")
    					.servletPath("/login")
    			        .with(csrf())
    			        .with(remoteAddress(sourceAddress))
    			        .param("email", EMAIL)
    			        .param("password", "wrong-password"))
    			.andExpect(status().is3xxRedirection())
    			.andExpect(redirectedUrl("/login?error"))
    			.andExpect(unauthenticated());
    }
    
    /**
     * HTTPリクエストに指定された接続元IPアドレスを設定する
     * リクエスト加工処理を生成します。
     * 
     * @param sourceAddress 設定する接続元IPアドレスです。
     * @return 接続元IPアドレスを設定するRequestPostProcessorを返します。
     */
    private RequestPostProcessor remoteAddress(String sourceAddress) {
    	return request -> {
    		request.setRemoteAddr(sourceAddress);
    		return request;
    	};
    }
    
    /**
     * ロールによるアクセス制御を検証するための
     * テスト用コントローラーです。
     * <p>
     * OPERATORロールのみアクセス可能なエンドポイントを提供し、
     * 権限不足の場合の動作を検証するために使用します。
     */
    @Controller
    static class RoleRestrictedController {
    	
    	/**
    	 * OPERATORロールを持ったユーザーに限定した
    	 * テスト用エンドポイントです。
    	 * 
    	 * @return テスト用の応答文字列を返します。
     	 */
    	@GetMapping("/test/operator-only")
    	@PreAuthorize("hasRole('OPERATOR')")
    	@ResponseBody
    	String operatorOnly() {
    		return "operator-only";
    	}
    }

}
