package jp.keepia.domain;

/**
 * 利用者アカウントの状態を表す列挙クラスです。
 */
public enum UserStatus {
	
	/** 招待済みだが、パスワード設定が完了していない状態 */
	INVITED,
	
	/** 認証及び利用が可能な状態 */
	ACTIVE,
	
	/** 管理者によって利用を停止された状態 */
	STOPPED
	

}
