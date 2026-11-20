package jp.keepia.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ログイン認証成功時の処理を担当するハンドラーです。
 * 
 * <p>認証済みユーザーの情報とアクセス元アドレスを使用し、
 *    ログイン失敗回数の記録をリセットした後、トップページへリダイレクトします。
 *    リセット処理で例外が発生した場合も、エラーログを出力して画面遷移を継続します。</p>
 */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {
	
	private static final Logger log = LoggerFactory.getLogger(LoginSuccessHandler.class);
	
	private final LoginAttemptService loginAttemptService;
	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
	
	/**
	 * ログイン成功時の処理に必要なサービスを設定します。
	 * 
	 * @param loginAttemptService ログイン試行回数を管理するサービス
	 */
	public LoginSuccessHandler(LoginAttemptService loginAttemptService) {
		this.loginAttemptService = loginAttemptService;
	}
	
	/**
	 * ログイン認証成功時に、失敗回数のリセットと画面遷移を行います。
	 * 
	 * <p>認証済みユーザーがAcountPrincipalの場合、
	 *    ユーザーのメールアドレスとアクセス元アドレスから識別キーを生成し、
	 *    対応するログイン失敗回数の記録をリセットします。
	 *    リセット処理で例外が発生した場合は、エラーログを出力し、
	 *    処理を継続してトプページへリダイレクトします。</p>
	 *    
	 * @param request 認証に成功したHTTPリクエスト
	 * @param response リダイレクト先を設定するHTTPレスポンス
	 * @param authentication 認証に成功したユーザーの認証情報
	 * @throws IOException リダイレクト処理で入出力エラーが発生した場合
	 * @throws ServletException サーブレット処理でエラーが発生した場合
	 */
	@Override
	public void onAuthenticationSuccess(
			HttpServletRequest request,
			HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {
		
		if(authentication.getPrincipal() instanceof AccountPrincipal principal) {
			try {
				loginAttemptService.recordSuccess(
						principal.getUsername(),
						request.getRemoteAddr());
			} catch(RuntimeException recordingFailure) {
				log.error("Failed to reset login failures after successful authentication", recordingFailure);
			}
		}
		redirectStrategy.sendRedirect(request, response, "/");
	}

}
