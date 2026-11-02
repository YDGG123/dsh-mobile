package com.clarklevis.dsh.android.update

/**
 * 「关于」里的版本自证串：`1.7.13 (50)`（短版本名 + versionCode）。
 *
 * 拆成独立函数只为**可测**：这个串只给显示用；版本比较必须用原始 `versionName`
 * （`AppUpdateViewModel.compareVersions` / `SettingsScreen` 的 releaseInfo 比对），
 * 把 code 拼进比较输入会同时弄坏「检查更新」和「本版本新功能」（iOS `:429` 同一个坑）。
 */
internal fun formatDisplayVersion(versionName: String?, versionCode: Long, unknownLabel: String): String {
    val name = versionName?.trim().orEmpty()
    if (name.isEmpty()) return unknownLabel
    return "$name ($versionCode)"
}
