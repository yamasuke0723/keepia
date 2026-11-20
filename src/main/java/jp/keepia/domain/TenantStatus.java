package jp.keepia.domain;

/**
 * 保守会社の利用状態を表す列挙クラスです。
 * 
 * <p>DBの{@code tenants.status}に文字列として保存するため、
 * 	  列挙値の名称を変更する場合はDBマイグレーションとの整合確認が必要となります。</p>
 */
public enum TenantStatus {
	
	/** 利用可能 */
	ACTIVE,
	
	/** 利用停止中 */
	STOPPED

}
