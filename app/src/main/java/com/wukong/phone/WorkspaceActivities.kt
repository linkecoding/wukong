package com.wukong.phone

/** Shared page renderer, distinct Android destinations; framework owns their back stack. */
class HistoryActivity : WorkspaceActivity() { override val destination = 1 }
class SettingsActivity : WorkspaceActivity() { override val destination = 2 }
class TaskDetailActivity : WorkspaceActivity() { override val destination = 4 }
class DiagnosticsActivity : WorkspaceActivity() { override val destination = 5 }
class RunDetailActivity : WorkspaceActivity() { override val destination = 6 }
