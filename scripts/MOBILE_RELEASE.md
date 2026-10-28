# Nuvio+ Mobile Beta releases

Mobile uses a dedicated Android release key at
`%USERPROFILE%\NuvioSigningKeys\NuvioPlusMobile\`. Its JKS and DPAPI-protected
password file are outside Git. Back up both securely; losing the key prevents
future in-place updates. The signing key is separate from Nuvio TV.

The one-time key was provisioned with `setup-mobile-release-signing.ps1`. To
publish a Mobile Beta update, increment the version code and run from this
repository in PowerShell:

```powershell
.\scripts\publish-mobile-beta.ps1 -VersionName '0.1.0-beta.3' -VersionCode 3
```

The command builds and signs a universal `fullRelease` APK, checks its package,
version and pinned certificate, uploads it to the existing Manager release
directory, preserves a unique copy of an existing Mobile Beta APK, and verifies
the public download's size and SHA-256. It only publishes
`https://manager.adenstack.com/nuvio-mobile-beta.apk`; it has no Stable action.
