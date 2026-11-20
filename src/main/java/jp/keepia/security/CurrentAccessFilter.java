package jp.keepia.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jp.keepia.config.SecurityProperties;
import jp.keepia.domain.AppUser;
import jp.keepia.domain.Customer;
import jp.keepia.domain.Tenant;
import jp.keepia.repository.AppUserRepository;
import jp.keepia.repository.CustomerRepository;
import jp.keepia.repository.TenantRepository;

/**
 * ログイン済みユーザーのアクセス権限とセッションの有効性を検証するフィルターです。
 * 
 * <p>認証後の各リクエストに対して、セッションの有効期限、
 *    ユーザー情報、所属先の有効状態などを確認します。
 *    認証状態が無効になっている場合はセッションを破棄し、
 *    ログイン画面へリダイレクトします。</p>
 *    
 *    servletコンテナへのフィルター自動登録を避けるため、
 *    @Componentは付与せず、SecurityFilterChainへ明示的に登録します。
 */
public class CurrentAccessFilter extends OncePerRequestFilter {
	
	private final AppUserRepository appUserRepository;
	private final TenantRepository tenantRepository;
	private final CustomerRepository customerRepository;
	private final SecurityProperties properties;
	private final Clock clock;
	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy(); 
	
	/**
	 * アクセス権限とセッションの憲章に必要な依存オブジェクトを設定します。
	 * 
	 * @param appUserRepository 利用者情報を管理するRepository
	 * @param tenantRepository 保守会社情報を管理するRepository
	 * @param customerRepository 顧客情報を管理するRepository
	 * @param properties セッションの有効期限などを管理するセキュリティ設定
	 * @param clock 現在時刻を取得する時刻源
	 */
	public CurrentAccessFilter(
			AppUserRepository appUserRepository,
			TenantRepository tenantRepository,
			CustomerRepository customerRepository,
			SecurityProperties properties,
			Clock clock) {
		this.appUserRepository = appUserRepository;
		this.tenantRepository = tenantRepository;
		this.customerRepository = customerRepository;
		this.properties = properties;
		this.clock = clock;
	}
	
	/**
	 * リクエスト時に認証情報と現在のアクセス権限を検証します。
	 * 
	 * <p>認証済みのAccountPrincipalが存在する場合、
	 *    セッションの有効期限と最新のユーザー情報を検証します。
	 *    無効な場合は認証情報とセッションの破棄し、
	 *    ログイン画面へリダイレクトします。
	 *    未認証または検証対象外の場合は、後続のフィルターへ処理を引き継ぎます。</p>
	 *    
	 * @param request 検証対象のHTTPリクエスト
	 * @param response HTTPレスポンス
	 * @param filterChain 後続のフィルター処理を管理するチェーン
	 * @throws ServletException フィルター処理中にサーブレット例外が発生した場合
	 * @throws IOException フィルター処理中に入出力エラーが発生した場合
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if(authentication == null
				|| !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
			filterChain.doFilter(request, response);
			return;
		}
		
		if(!isCurrent(principal, clock.instant())) {
			invalidateSession(request);
			redirectStrategy.sendRedirect(request, response, "/login?expired");
			return;
		}
		filterChain.doFilter(request, response);
	}
	
	/**
	 * 認証済みユーザーのアクセス権限が現在も有効かを判定します。
	 * 
	 * <p>認証時刻からの絶対有効期限を確認した後、
	 *    データベース上の最新のユーザー情報と認証時の情報を比較します。
	 *    ユーザーの有効状態、所属情報、権限、セキュリティバージョンを検証し、
	 *    運営者以外は所属する保守会社の有効状態も確認します。
	 *    顧客ユーザーの場合は、顧客の存在と有効状態も検証します。</p>
	 *    
	 * @param principal 認証時に生成されたユーザー情報
	 * @param now 検証時点の現在時刻
	 * @return すべての検証条件を満たす場合はtrue、それ以外はfalse
	 */
	private boolean isCurrent(AccountPrincipal principal, Instant now) {
		if(!now.isBefore(principal.getAuthenticatedAt()
				.plus(properties.absoluteSessionTimeout()))) {
			return false;
		}
		
		AppUser user = appUserRepository.findById(principal.getUserId()).orElse(null);
		if(user == null
				|| !user.isActive()
				|| !user.hasValidAssignment()
				|| user.getSecurityVersion() != principal.getSecurityVersion()
				|| user.getRole() != principal.getRole()
				|| !Objects.equals(user.getTenantId(), principal.getTenantId())
				|| !Objects.equals(user.getCustomerId(), principal.getCustomerId())) {
			return false;
		}
		
		if(user.isOperator()) {
			return true;
		}
		
		Tenant tenant = tenantRepository.findById(user.getTenantId()).orElse(null);
		if(tenant == null || !tenant.isActive()) {
			return false;
		}
		
		if(!user.isCustomer()) {
			return true;
		}
		
		Customer customer = customerRepository
				.findByIdAndTenantId(user.getCustomerId(), user.getTenantId())
				.orElse(null);
		return customer != null && customer.isActive();
	}
	
	/**
	 * 現在の認証情報とHTTPセッションを無効化します。
	 * <p>Spring SecurityのSecurityContextを消去し、
	 *    既存のHTTPセッションが存在する場合は破棄します。
	 *    セッションが存在しない場合は新規作成しません。</p>
	 *    
	 * @param request セッション情報を取得するHTTPリクエスト
	 */
	private void invalidateSession(HttpServletRequest request) {
		SecurityContextHolder.clearContext();
		HttpSession session = request.getSession(false);
		if(session != null) {
			session.invalidate();
		}
	}
	
	/**
	 * リクエストがアクセス権限の検証対象外かを判定します。
	 * 
	 * <p>ログイン画面、アクセス拒否画面、エラー画面、
	 * CSS、JavaScript、画像ファイルへのリクエストは検証を省略します。
	 * それ以外のリクエストはフィルター処理の対象とします。
	 * 
	 * @param request 判定対象のHTTPリクエスト
	 * @return フィルター処理を省略する場合はtrue、実行する場合はfalse
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getServletPath();
		return path.equals("/login")
				|| path.equals("/access-denied")
				|| path.equals("/error")
				|| path.startsWith("/css/")
				|| path.startsWith("/js/")
				|| path.startsWith("/images/");
	}
	
}
