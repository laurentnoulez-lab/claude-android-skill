; Installateur Windows de MAO Moderne (NSIS)
; Compilé avec : makensis -DVERSION=x.y -DPUBLISH_DIR=<dossier publish> MaoModerne.nsi

!ifndef VERSION
  !define VERSION "1.0"
!endif
!ifndef PUBLISH_DIR
  !define PUBLISH_DIR "publish"
!endif

Unicode true
Name "MAO Moderne ${VERSION}"
OutFile "MaoModerne-Setup-${VERSION}.exe"
InstallDir "$PROGRAMFILES64\MAO Moderne"
InstallDirRegKey HKLM "Software\MaoModerne" "InstallDir"
RequestExecutionLevel admin
SetCompressor /SOLID lzma

!include "MUI2.nsh"

!define MUI_ABORTWARNING
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\MaoModerne.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Lancer MAO Moderne"
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "French"

Section "MAO Moderne" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"
  File "${PUBLISH_DIR}\MaoModerne.exe"
  File /r "${PUBLISH_DIR}\LatoFont"

  WriteRegStr HKLM "Software\MaoModerne" "InstallDir" "$INSTDIR"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  ; Raccourcis menu Démarrer + bureau
  CreateDirectory "$SMPROGRAMS\Qualiroutes"
  CreateShortcut "$SMPROGRAMS\Qualiroutes\MAO Moderne.lnk" "$INSTDIR\MaoModerne.exe"
  CreateShortcut "$SMPROGRAMS\Qualiroutes\Désinstaller MAO Moderne.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortcut "$DESKTOP\MAO Moderne.lnk" "$INSTDIR\MaoModerne.exe"

  ; Association du type de fichier .mao (métré)
  WriteRegStr HKLM "Software\Classes\.mao" "" "MaoModerne.Metre"
  WriteRegStr HKLM "Software\Classes\MaoModerne.Metre" "" "Métré MAO"
  WriteRegStr HKLM "Software\Classes\MaoModerne.Metre\DefaultIcon" "" "$INSTDIR\MaoModerne.exe,0"

  ; Désinstallation via Panneau de configuration
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MaoModerne" \
      "DisplayName" "MAO Moderne — Métré Assisté par Ordinateur"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MaoModerne" \
      "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MaoModerne" \
      "Publisher" "MAO Moderne"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MaoModerne" \
      "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MaoModerne" \
      "DisplayIcon" "$INSTDIR\MaoModerne.exe"
SectionEnd

Section "Uninstall"
  Delete "$INSTDIR\MaoModerne.exe"
  RMDir /r "$INSTDIR\LatoFont"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"

  Delete "$SMPROGRAMS\Qualiroutes\MAO Moderne.lnk"
  Delete "$SMPROGRAMS\Qualiroutes\Désinstaller MAO Moderne.lnk"
  RMDir "$SMPROGRAMS\Qualiroutes"
  Delete "$DESKTOP\MAO Moderne.lnk"

  DeleteRegKey HKLM "Software\Classes\.mao"
  DeleteRegKey HKLM "Software\Classes\MaoModerne.Metre"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MaoModerne"
  DeleteRegKey HKLM "Software\MaoModerne"

  ; NB : la base de données (%AppData%\MaoModerne) est volontairement conservée.
SectionEnd
