# Hisab Ledger

Offline Android ledger for Withdrawal and Deposit tracking.

## Final rules
- Withdrawal = GREEN
- Deposit = RED
- Balance = Total Withdrawal - Total Deposit
- Date and time are stored with every transaction.

## Included in v1.1
- Add, edit and delete Withdrawal / Deposit
- Today / This Month / All Time totals and counts
- Transaction history, search and type filters
- Custom Date Report
- Monthly Calendar View
- PDF reports: custom range, current month and all time
- CSV export
- JSON backup/restore
- Daily automatic local backup (keeps the latest 7 snapshots inside app-private storage)
- PIN lock
- Fingerprint / biometric lock (requires an App PIN as fallback)
- Dark / Light theme
- Android 15 edge-to-edge system-bar safe layout

## Codemagic
Use the `android-debug` workflow for an installable debug APK.
Use `android-release` after configuring signing in Codemagic for a distributable release APK.

## Important
Automatic backup is a local on-device snapshot. It is not cloud backup. For phone-loss protection, use the manual Backup option and save the JSON file to a safe location/cloud drive.
