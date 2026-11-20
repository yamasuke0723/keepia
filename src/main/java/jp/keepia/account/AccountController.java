package jp.keepia.account;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

import jp.keepia.security.AccountPrincipal;

/**
 * アカウント認証に関連する画面表示を担当するControllerクラスです。
 * 
 * <p>ログイン画面、認証済みユーザーのホーム画面、
 * アクセス権限が付属している場合のエラー画面を表示します。
 */
@Controller
public class AccountController {
	
	/**
	 * ログイン画面を表示します。
	 * 
	 * @return ログイン画面のテンプレート名
	 */
	@GetMapping("/login")
	public String login() {
		return "login";
	}
	
	/**
	 * 認証済みユーザーのホーム画面を表示します。
	 * 
	 * <p>Spring Securityから認証済みユーザー情報を取得し、
	 * 表示名とロールをModelに設定してホーム画面へ渡します。
	 * 
	 * @param principal 認証済みユーザーの情報
	 * @param model 画面表示に使用するデータを保持するModel
	 * @return ホーム画面のテンプレート名
	 */
	@GetMapping("/")
	public String home(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		model.addAttribute("displayName", principal.getDisplayName());
		model.addAttribute("role", principal.getRole().name());
		return "home";
	}
	
	/**
	 * アクセス権限が不足している場合のエラー画面を表示します。
	 * 認証済みであっても、権限不足の場合同様に処理をします。
	 * <p>HTTPステータス403(Forbidden)を設定し、
	 * アクセス拒否用のエラー画面を返します。
	 * 
	 * @return アクセス拒否画面のテンプレート名
	 */
	@GetMapping("/access-denied")
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public String accessDenied() {
		return "error/403";
	}

}
