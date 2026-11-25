; BitChord's Windows installer wizard.
;
; Compile after :desktopApp:createDistributable, which lays out the app image
; this script packages:
;
;   iscc "/DAppVersion=1.9.1" "/Obuild\installer" desktopApp\packaging\windows\installer.iss
;
; .github/workflows/windows-exe.yml does the same on the Windows runner. The
; /D value is the version already resolved for jpackage; without it, a bare
; local compile falls back to the default below.

#ifndef AppVersion
  #define AppVersion "1.0.1"
#endif

#define AppName "BitChord"
#define AppPublisher "BitChord contributors"
; The product identity jpackage's windows.upgradeUuid
; (desktopApp/build.gradle.kts) carries, kept the same here so both packages
; present BitChord under one identity. The doubled brace is Inno's escape for a
; literal "{".
#define AppId "{{8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913}"
#define AppExeName "BitChord.exe"

[Setup]
AppId={#AppId}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
; Matches the app's per-user, no-administrator install (windows.perUserInstall).
PrivilegesRequired=lowest
DefaultDirName={autopf}\BitChord
; Wizard pages: Welcome, Destination, Tasks, Ready, Installing, Finished. The
; program-group page is left out; the shortcuts are still created.
DisableWelcomePage=no
DisableDirPage=no
DisableProgramGroupPage=yes
; The default output used by a local compile; CI overrides it with /O to stage
; the setup beside the other Windows artifacts.
OutputDir=..\..\build\installer
OutputBaseFilename={#AppName}-{#AppVersion}-windows-x64-inno-setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern dynamic
SetupIconFile=..\icons\AppIcon.ico
UninstallDisplayIcon={app}\BitChord.exe
UninstallDisplayName={#AppName} {#AppVersion}

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "autostart"; Description: "Start {#AppName} when I sign in to Windows"; GroupDescription: "Startup"

[Files]
; The complete createDistributable image: runtime, jars, natives and models.
; Nothing debug-only belongs in an installer, so symbol and linker
; intermediates are excluded by pattern rather than trusted to be absent: a
; Visual Studio native build can leave .pdb/.ilk beside a DLL, and including
; them costs compression time and install size for files no one runs.
Source: "..\..\build\compose\binaries\main\app\BitChord\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "*.pdb,*.ilk,*.lib,*.exp,*.map,*.ipdb,*.iobj,*.dSYM\*"

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{userstartup}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: autostart

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[Code]
// Custom wizard pages and install-time preferences belong here. Both hooks are
// intentionally empty so the default flow stays unchanged; add a
// CreateCustomPage(...) in InitializeWizard, or write settings from
// CurStepChanged when CurStep is ssPostInstall.
procedure InitializeWizard();
begin
  // Custom wizard pages go here.
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    // Install-time preferences go here.
  end;
end;
