package jp.keepia.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ログイン認証失敗時の処理を担当するハンドラーです。
 * 
 * <p>認証失敗時にメールアドレスとアクセス元アドレスを取得し、
 *    ログイン失敗回数を記録した後、ログイン画面へリダイレクトします。
 *    記録処理で例外が発生した場合も、エラーログを出力してリダイレクトを行います。</p>
 */
@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {
	
	private static final Logger log = LoggerFactory.getLogger(LoginFailureHandler.class);
	
	private final LoginAttemptService loginAttemptService;
	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
	
	/**
	 * ログイン失敗時の記録処理に必要なサービスを設定します。
	 * 
	 * @param loginAttemptService ログイン試行回数を管理するサービス
	 */
	public LoginFailureHandler(LoginAttemptService loginAttemptService) {
		this.loginAttemptService = loginAttemptService;
	}
	
	/**
	 * ログイン認証失敗時に、失敗回数の記録と画面遷移を行います。
	 * 
	 * <p>リクエストからメールアドレスとアクセス元アドレスを取得し、
	 *    ログイン失敗を記録します。
	 *    記録処理で例外が発生した場合は、エラーログを出力し、
	 *    処理を継続してログイン画面へリダイレクトします。</p>
	 *    
	 * @param request 認証に失敗したHTTPリクエスト
	 * @param response リダイレクト先を設定するHTTPレスポンス
	 * @param exception 認証失敗の原因となった例外
	 * @throws IOException リダイレクト処理で入出力エラーが発生した場合
	 * @throws ServletException サーブレット処理でエラーが発生した場合
	 */
	@Override
	public void onAuthenticationFailure(
			HttpServletRequest request,
			HttpServletResponse response,
			AuthenticationException exception) throws IOException, ServletException {
		
		String email = request.getParameter("email");
		String sourceAddress = request.getRemoteAddr();
		try {
			loginAttemptService.recordFailure(email, sourceAddress);
		} catch (RuntimeException recordingFailure) {
			//パスワードやメールアドレスはログへ記録しません。
			log.error("Failure to record a login failure", recordingFailure);
		}
		redirectStrategy.sendRedirect(request, response, "/login?error");
		
	}

}
