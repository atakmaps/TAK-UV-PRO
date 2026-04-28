Local scratch folder (not committed except this file)

Save logcat or other diagnostic text files here as .txt so they can be read from the repo
workspace without pasting into chat.

Suggested names:
  logcat-btech-chat.txt
  logcat-full-send-attempt.txt

To capture on your machine (adjust package/device as needed):

  adb logcat -d > scratch/logcat-dump.txt

Or filter:

  adb logcat -d -s BtechRelay:V CotBridge:V ChatBridge:V *:S

This directory is gitignored for any file except README.txt — your logs stay local unless
you force-add them (please do not commit secrets or large binaries).
