package jp.keepia.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jp.keepia.repository.AppUserRepository;
import jp.keepia.repository.CustomerRepository;
import jp.keepia.repository.TenantRepository;
import jp.keepia.security.CurrentAccessFilter;
import jp.keepia.security.LoginAttemptService;
import jp.keepia.security.LoginFailureHandler;
import jp.keepia.security.LoginRateLimitFilter;
import jp.keepia.security.LoginSuccessHandler;

/**
 * アプリケーション全体のセキュリティ設定を行うクラスです。
 * 
 * <p>
 * パスワードの照合方法、ログイン・ログアウト処理、
 * URLへのアクセス制御、セッション管理などを設定します。
 * <p>
 * また、ログイン試行回数の制限や、ログイン後のアクセス状態を
 * 確認する独自フィルターをSpring Securityに登録します。
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {
	
	/**
	 * 現在時刻を取得するためのClockを生成します。
	 * <p>
	 * UTCを基準とした時刻を取得することで、
	 * 時刻を利用する各処理の基準を統一します。
	 * 
	 * @return UTCを基準とするClock
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
	
	/**
	 * パスワードのハッシュ化と照合を行うエンコーダーを生成します。
	 * <p>
	 * BCrypt方式を利用し、パスワードを安全に保存・照合します。
	 * 
	 * @return BCrypt方式のPasswordEncoder
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
	
	/**
	 * ユーザー認証を行うプロバイダーを生成します。
	 * <p>
	 * UserDetailsServiceからユーザー情報を取得し、
	 * PasswordEncoderを利用してパスワードを照合します。
	 * 
	 * @param userDetailsService ユーザー情報を取得するサービス
	 * @param passwordEncoder パスワードの照合に使用するエンコーダー
	 * @return ユーザー認証を行うDaoAuthenticationProvider
	 */
	@Bean
	public DaoAuthenticationProvider daoAuthenticationProvider(
			UserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return provider;
		
	}
	
	/**
	 * HTTPリクエストに適用するセキュリティ設定を構築します。
	 * <p>
	 * URL五とのアクセス制御、フォーム認証、
	 * ログアウト、セッション管理、アクセス拒否時の
	 * 処理などを設定します。
	 * <p>
	 * また、ログイン試行回数を制限するフィルターと、
	 * ログイン後のアクセス状態を確認するフィルターを登録します。
	 * 
	 * @param http HTTPセキュリティ設定を構築するオブジェクト
	 * @param authenticationProvider ユーザー認証を行うプロバイダー
	 * @param loginAttemptService ログイン試行状態を管理するサービス
	 * @param loginFailureHandler ログイン失敗時の処理
	 * @param loginSuccessHandler ログイン成功時の処理
	 * @param appUserRepository アプリケーションユーザーのリポジトリ
	 * @param tenantRepository テナント情報のリポジトリ
	 * @param customerRepository 顧客情報のリポジトリ
	 * @param properties セキュリティ関連の設定値
	 * @param clock 現在時刻を取得するためのClock
	 * @return 構築したSecurityFilterChain
	 * @throws Exception セキュリティ設定の構築に失敗した場合
	 */
	@Bean
	public SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			DaoAuthenticationProvider authenticationProvider,
			LoginAttemptService loginAttemptService,
			LoginFailureHandler loginFailureHandler,
			LoginSuccessHandler loginSuccessHandler,
			AppUserRepository appUserRepository,
			TenantRepository tenantRepository,
			CustomerRepository customerRepository,
			SecurityProperties properties,
			Clock clock) throws Exception {
		
		LoginRateLimitFilter rateLimitFilter = 
				new LoginRateLimitFilter(loginAttemptService);
		CurrentAccessFilter currentAccessFilter = new CurrentAccessFilter(
				appUserRepository,
				tenantRepository,
				customerRepository,
				properties,
				clock);
		
		http.authenticationProvider(authenticationProvider);
		
		http.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(
						"/login",
						"/access-denied",
						"/error",
						"/css/**",
						"/js/**",
						"/images/**")
				.permitAll()
				.anyRequest()
				.authenticated());
		
		http.formLogin(form -> form
				.loginPage("/login")
				.loginProcessingUrl("/login")
				.usernameParameter("email")
				.passwordParameter("password")
				.successHandler(loginSuccessHandler)
				.failureHandler(loginFailureHandler)
				.permitAll());
		
		http.logout(logout -> logout
				.logoutUrl("/logout")
				.logoutSuccessUrl("/login?logout")
				.invalidateHttpSession(true)
				.clearAuthentication(true)
				.deleteCookies("JSESSIONID"));
		
		http.sessionManagement(session -> session
				.sessionFixation(fixation -> fixation.changeSessionId())
				.invalidSessionUrl("/login?expired"));
		
		http.exceptionHandling(exceptions -> exceptions
				.accessDeniedPage("/access-denied"));
		
		//CSRFはSpring Securityの規定値で有効。無効化しません。
		http.addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);
		http.addFilterAfter(currentAccessFilter, UsernamePasswordAuthenticationFilter.class);
		
		return http.build();
		
	}

}
