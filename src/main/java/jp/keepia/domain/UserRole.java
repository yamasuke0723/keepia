package jp.keepia.domain;

/**
 * Keepia利用者の役割を表す列挙クラスです。
 * 
 * <p>Spring Securityへ渡す際は、列挙値の先頭に{@code ROLE_}を付けた
 *    権限名へ変換します。</p>
 */
public enum UserRole {
	
	/** keepia全体を管理する運営者。保守会社には所属しません。 */
	OPERATOR,
	
	/** 保守会社の管理者 */
	ADMIN,
	
	/** 保守会社の担当者 */
	STAFF,
	
	/** 顧客窓口の利用者 */
	CUSTOMER;
	
	/**
	 * Spring Securityで使用する権限名を返します。
	 * 
	 * @return {@code ROLE_OPERATOR}のような権限名
	 */
	public String authority() {
		return "ROLE_" + name();
	}

}
