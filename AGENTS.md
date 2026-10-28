# Agent / contributor instructions

Before making changes, read `docs/REMEDIATION.md`. It is the durable task ledger
for the current remediation effort and contains the goal, status, decisions, and
verification commands.

- Continue from the first item marked `[ ]` or `[~]`, unless the user gives a
  different priority.
- Keep `docs/REMEDIATION.md` up to date in the same change: mark statuses, list
  files touched, and add verification evidence.
- Do not rely on prior chat history. Verify assumptions against the current code.
- Do not add the 63 MB voice model to normal Git history; use the Gradle
  provisioning task documented in the plan.
- Do not claim a device/instrumentation test passed unless one was actually run.
- Preserve existing user changes; inspect `git status` and the diff first.
