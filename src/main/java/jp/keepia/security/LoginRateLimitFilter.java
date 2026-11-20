package jp.keepia.security;

import java.io.IOException;

import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ログイン認証前にアクセス制限状態を確認するフィルターです。
 * 
 * <p>ログイン要求のメールアドレスとアクセス元アドレスを使用し、
 *    ログイン試行回数に基づくアクセス制限状態を判定します。
 *    制限中の場合は認証処理を中断し、ログイン画面へリダイレクトします。
 *    制限されていない場合は、後続のフィルター処理へ進みます。
 *    Servletコンテナへのフィルター自動登録を避けるため、
 * 	  @Componentは付与せず、SecurityFilterChainへ明示的に登録します。</p>
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {
	
	private final LoginAttemptService loginAttemptService;
	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
	
	/**
	 * ログイン試行制限の判定に必要なサービスを設定します。
	 * 
	 * @param loginAttemptService ログイン試行回数とアクセス制限を管理するサービス
	 */
	public LoginRateLimitFilter(LoginAttemptService loginAttemptService) {
		this.loginAttemptService = loginAttemptService;
	}
	
	/**
	 * ログイン要求に対してアクセス制限状態を確認します。
	 * 
	 * <p>リクエストからメールアドレスとアクセス元アドレスを取得し、
	 *    ログイン試行が制限されているかを判定します。
	 *    制限中の場合は通常の認証失敗と同じ画面へリダイレクトし、
	 *    後続のフィルター処理を実行しません。
	 *    制限されていない場合は、後続のフィルターへ処理を引き継ぎます。</p>
	 *    
	 * @param request ログイン要求を含むHTTPリクエスト
	 * @param response HTTPレスポンス
	 * @param filterChain 後続のフィルター処理を管理するチェーン
	 * @throws ServletException フィルター処理中にサーブレット例外が発生した場合
	 * @throws IOException フィルター処理中に入出力エラーが発生した場合    
	 * 
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		
		String email = request.getParameter("email");
		String sourceAddress = request.getRemoteAddr();
		if(loginAttemptService.isBlocked(email, sourceAddress)) {
			//アカウントの存在有無を明かさないため通常の認証失敗と同じ表示にします。
			redirectStrategy.sendRedirect(request, response, "/login?error");
			return;
		}
		filterChain.doFilter(request, response);
	}
	
	/**
	 * リクエストがログイン試行制限の判定対象外かを判定します。
	 * 
	 * <p>POSTメソッドによる/loginへのリクエストのみを判定対象とし、
	 *    それ以外のリクエストではフィルター処理を省略します。</p>
	 *    
	 * @param request 判定対象のHTTPリクエスト
	 * @return フィルター処理を省略する場合はtrue,実行する場合はfalse
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !("POST".equalsIgnoreCase(request.getMethod())
					&& "/login".equals(request.getServletPath()));
	}

}
