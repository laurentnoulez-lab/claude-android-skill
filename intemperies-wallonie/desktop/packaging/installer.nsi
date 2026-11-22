; Installateur Windows d'Intempéries Wallonie (NSIS).
; Installation par utilisateur (aucun droit administrateur), raccourcis, désinstallation via les Paramètres Windows.
; Les données (paramètres, communes, mesures) restent dans %APPDATA%\IntemperiesWallonie : conservées lors des mises à jour.
Unicode true
!include "MUI2.nsh"

!define APP "Intempéries Wallonie"
!define KEY "IntemperiesWallonie"
!define EXE "IntemperiesWallonie.exe"
!define UNINST "Software\Microsoft\Windows\CurrentVersion\Uninstall\${KEY}"

Name "${APP}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\${KEY}"
InstallDirRegKey HKCU "Software\${KEY}" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
BrandingText "${APP} ${VERSION}"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1036 "ProductName" "${APP}"
VIAddVersionKey /LANG=1036 "FileDescription" "Installation de ${APP}"
VIAddVersionKey /LANG=1036 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1036 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=1036 "LegalCopyright" "Sources des données : SPW"

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TEXT "Cet assistant installe ${APP} ${VERSION}.$\r$\n$\r$\nL'application calcule les journées d'intempéries des chantiers à partir des pluviomètres et stations de température du SPW. Java est inclus : rien d'autre à installer.$\r$\n$\r$\nVos paramètres et données sont conservés lors des mises à jour."
!define MUI_FINISHPAGE_RUN "$INSTDIR\${EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "Lancer ${APP}"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "French"

Section "Installer"
  ; Mise à jour : l'application doit être fermée (fichiers en cours d'utilisation).
  retry:
  ClearErrors
  IfFileExists "$INSTDIR\${EXE}" 0 +4
    Delete "$INSTDIR\${EXE}"
    IfErrors 0 +2
      MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "Fermez ${APP} puis cliquez sur Réessayer." IDRETRY retry IDCANCEL abort
  RMDir /r "$INSTDIR\runtime"
  SetOutPath "$INSTDIR"
  File /r "${SRC}\*.*"
  WriteUninstaller "$INSTDIR\Desinstaller.exe"
  WriteRegStr HKCU "Software\${KEY}" "InstallDir" "$INSTDIR"

  CreateShortCut "$SMPROGRAMS\${APP}.lnk" "$INSTDIR\${EXE}" "" "$INSTDIR\${EXE}" 0
  CreateShortCut "$DESKTOP\${APP}.lnk" "$INSTDIR\${EXE}" "" "$INSTDIR\${EXE}" 0

  WriteRegStr HKCU "${UNINST}" "DisplayName" "${APP}"
  WriteRegStr HKCU "${UNINST}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST}" "Publisher" "${APP}"
  WriteRegStr HKCU "${UNINST}" "DisplayIcon" "$INSTDIR\${EXE}"
  WriteRegStr HKCU "${UNINST}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST}" "UninstallString" "$\"$INSTDIR\Desinstaller.exe$\""
  WriteRegDWORD HKCU "${UNINST}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST}" "NoRepair" 1
  WriteRegDWORD HKCU "${UNINST}" "EstimatedSize" 180000
  Return
  abort:
  Abort
SectionEnd

Section "Uninstall"
  Delete "$SMPROGRAMS\${APP}.lnk"
  Delete "$DESKTOP\${APP}.lnk"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKCU "${UNINST}"
  DeleteRegKey HKCU "Software\${KEY}"
  ; Les données de %APPDATA%\IntemperiesWallonie sont volontairement conservées.
SectionEnd
