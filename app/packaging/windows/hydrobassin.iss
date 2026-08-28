; Installeur Windows de HydroBassin (Inno Setup 6).
; Compilé par le workflow « Build Windows » :
;   iscc /DSourceDir=... /DExeName=... /DMaVersion=... /DOutputDir=... hydrobassin.iss

#ifndef MonApp
  #define MonApp "HydroBassin"
#endif
#ifndef MaVersion
  #define MaVersion "1.0.0"
#endif
#ifndef ExeName
  #define ExeName "HydroBassin.exe"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\build\windows"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\..\livrables"
#endif

[Setup]
AppId={{7B5C1E44-2E4B-4C1E-9F2E-2A1D6C3B8E10}
AppName={#MonApp}
AppVersion={#MaVersion}
AppVerName={#MonApp} {#MaVersion}
AppPublisher=HydroBassin
AppComments=Dimensionnement de bassins d'orage - methode rationnelle, pluies GTI
DefaultDirName={autopf}\{#MonApp}
DefaultGroupName={#MonApp}
DisableProgramGroupPage=yes
UninstallDisplayName={#MonApp} {#MaVersion}
UninstallDisplayIcon={app}\{#ExeName}
OutputDir={#OutputDir}
OutputBaseFilename={#MonApp}-Setup-{#MaVersion}
SetupIconFile=..\..\src\assets\icon.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; Installation possible sans droits administrateur (dossier utilisateur),
; l'utilisateur peut choisir une installation pour tous les comptes.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "raccourcibureau"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MonApp}"; Filename: "{app}\{#ExeName}"
Name: "{group}\{cm:UninstallProgram,{#MonApp}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MonApp}"; Filename: "{app}\{#ExeName}"; Tasks: raccourcibureau

[Run]
Filename: "{app}\{#ExeName}"; Description: "{cm:LaunchProgram,{#MonApp}}"; Flags: nowait postinstall skipifsilent
