package jp.keepia.security;

import java.time.Clock;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.keepia.domain.AppUser;
import jp.keepia.domain.Customer;
import jp.keepia.domain.Tenant;
import jp.keepia.repository.AppUserRepository;
import jp.keepia.repository.CustomerRepository;
import jp.keepia.repository.TenantRepository;

/**
 * メールアドレスから利用者と所属先の現在状態を読み込み、
 * Spring Securityの認証情報へ変換するServiceクラスです。
 */
@Service
public class AccountUserDetailsService implements UserDetailsService {
	
	private static final String GENERIC_AUTHENTICATION_ERROR = "Authentication is unavailable";
	
	private final AppUserRepository appUserRepository;
	private final TenantRepository tenantRepository;
	private final CustomerRepository customerRepository;
	private final Clock clock;
	
	/**
	 * 認証に必要なRepositoryと時刻源を受け取ります。
	 * 
	 * @param appUserRepository 利用者Repository
	 * @param tenantRepository 保守会社Repository
	 * @param customerRepository 顧客Repository
	 * @param clock 認証時刻を取得する時刻源
	 */
	public AccountUserDetailsService(
			AppUserRepository appUserRepository,
			TenantRepository tenantRepository,
			CustomerRepository customerRepository,
			Clock clock) {
		this.appUserRepository = appUserRepository;
		this.tenantRepository = tenantRepository;
		this.customerRepository = customerRepository;
		this.clock = clock;
	}
	
	/**
	 * ログイン用メールアドレスから認証対象を読み込みます。
	 * 
	 * @param username ログイン画面から受け取ったメールアドレス
	 * @return Spring Securityがパスワード照合に使用する利用者情報
	 * @throws UsernameNotFoundException 利用者が存在しない場合
	 * @throws DisabledException 利用者または所属先が利用できない場合
	 */
	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException{
		String email = EmailNormalizer.normalize(username);
		if(email.isBlank()) {
			throw new UsernameNotFoundException(GENERIC_AUTHENTICATION_ERROR);
		}
		
		AppUser user = appUserRepository.findByEmail(email)
				.orElseThrow(() -> new UsernameNotFoundException(GENERIC_AUTHENTICATION_ERROR));
		
		validateUser(user);
		return AccountPrincipal.from(user, clock.instant());
	}
	
	/**
	 * 認証対象ユーザーと所属先の利用可否を検証します。
	 * 
	 * ユーザーの有効状態、パスワードの登録状態、所属情報の整合性を確認します。
	 * 運営者の場合は、ユーザー自身の検証のみを行います。
	 * それ以外の場合は所属する保守会社の有効状態を確認し、
	 * 顧客ユーザーの場合は、所属する顧客の存在と保守会社との関連を確認します。
	 * 
	 * @param user 検証対象のユーザー
	 * @throws DisabledException ユーザーまたは所属先が認証条件を満たさない場合
	 */
	private void validateUser(AppUser user) {
		if(!user.isActive() || !user.hasPassword() || !user.hasValidAssignment()) {
			throw new DisabledException(GENERIC_AUTHENTICATION_ERROR);
		}
		
		if(user.isOperator()) {
			return;
		}
		
		Tenant tenant = tenantRepository.findById(user.getTenantId())
				.orElseThrow(() -> new DisabledException(GENERIC_AUTHENTICATION_ERROR));
		if(!tenant.isActive()) {
			throw new DisabledException(GENERIC_AUTHENTICATION_ERROR);
		}
		
		if(user.isCustomer()) {
			Customer customer = customerRepository
					.findByIdAndTenantId(user.getCustomerId(), user.getTenantId())
					.orElseThrow(() -> new DisabledException(GENERIC_AUTHENTICATION_ERROR));
			if(!customer.isActive()) {
				throw new DisabledException(GENERIC_AUTHENTICATION_ERROR);
			}
		}
	}
	
}
