<img width="505" height="128" alt="logo" src="https://github.com/user-attachments/assets/272af339-23d9-4afc-abd8-2cb255665cf9" />

WinlatorXR is a port of Winlator for Meta Quest and Pico headsets. It uses 2D/VR hybrid app flow documented by [amwatson](https://github.com/amwatson/2DVrHybrid).

* Winlator is originally developed by [brunodev85](https://github.com/brunodev85/winlator) but as he does not opensource the latest versions then I am forced to use a custom fork for this project.
* In 2024 WinlatorXR was based on Glibc fork by [longjunyu2](https://github.com/longjunyu2/winlator) but as he stopped communicating, the project was inactive for a long time and later it stopped working.
* In 2025 [Pipetto-crypto](https://github.com/Pipetto-crypto/winlator) ported Winlator to Bionic/Proton which is a massive boost for Meta Quest integration.
* His integration was enhanced by [coffincolors](https://github.com/coffincolors/winlator) in Winlator CMOD fork which is now base for WinlatorXR.
* Pico support and XR enhancement were brought by [Tobbe85](https://github.com/tobbe85/winlator).

![WinlatorXR history](https://github.com/user-attachments/assets/947c4be7-fe9e-435e-92c9-9ca1d55bef79)


### How to compile

1.Clone repository with all submodules
`git clone --recursive git@github.com:lvonasek/winlator.git`

2.Donwload the latest APK from releases

3.Unzip the APK and copy the content of assets into app/src/main/assets/ (except dexopt folder)

4.Open the project in Android Studio and have fun :)

---

Original README:

<p align="center">
	<img src="logo.png" width="376" height="128" alt="Winlator Logo" />  
</p>

# Winlator

Winlator is an Android application that lets you to run Windows (x86_64) applications with Wine and Box86/Box64.

# Installation

1. Download and install the APK (Winlator_7.1.apk) from [GitHub Releases](https://github.com/brunodev85/winlator/releases)
2. Launch the app and wait for the installation process to finish

----

[![Play on Youtube](https://img.youtube.com/vi/8PKhmT7B3Xo/1.jpg)](https://www.youtube.com/watch?v=8PKhmT7B3Xo)
[![Play on Youtube](https://img.youtube.com/vi/9E4wnKf2OsI/2.jpg)](https://www.youtube.com/watch?v=9E4wnKf2OsI)
[![Play on Youtube](https://img.youtube.com/vi/czEn4uT3Ja8/2.jpg)](https://www.youtube.com/watch?v=czEn4uT3Ja8)
[![Play on Youtube](https://img.youtube.com/vi/eD36nxfT_Z0/2.jpg)](https://www.youtube.com/watch?v=eD36nxfT_Z0)

----

# Useful Tips

- If you are experiencing performance issues, try changing the Box86/Box64 preset in Container Settings -> Advanced Tab.
- For applications that use .NET Framework, try installing Wine Mono found in Start Menu -> System Tools.
- If some older games don't open, try adding the environment variable MESA_EXTENSION_MAX_YEAR=2003 in Container Settings -> Environment Variables.
- Try running the games using the shortcut on the Winlator home screen, there you can define individual settings for each game.
- To speed up the installers, try changing the Box86/Box64 preset to Intermediate in Container Settings -> Advanced Tab.

# Credits and Third-party apps
- Ubuntu RootFs ([Focal Fossa](https://releases.ubuntu.com/focal))
- Wine ([winehq.org](https://www.winehq.org/))
- Box86/Box64 by [ptitseb](https://github.com/ptitSeb)
- PRoot ([proot-me.github.io](https://proot-me.github.io))
- Mesa (Turnip/Zink/VirGL) ([mesa3d.org](https://www.mesa3d.org))
- DXVK ([github.com/doitsujin/dxvk](https://github.com/doitsujin/dxvk))
- VKD3D ([gitlab.winehq.org/wine/vkd3d](https://gitlab.winehq.org/wine/vkd3d))
- D8VK ([github.com/AlpyneDreams/d8vk](https://github.com/AlpyneDreams/d8vk))
- CNC DDraw ([github.com/FunkyFr3sh/cnc-ddraw](https://github.com/FunkyFr3sh/cnc-ddraw))

Many thanks to [ptitSeb](https://github.com/ptitSeb) (Box86/Box64), [Danylo](https://blogs.igalia.com/dpiliaiev/tags/mesa/) (Turnip), [alexvorxx](https://github.com/alexvorxx) (Mods/Tips) and others.

Thank you to all the people who believe in this project.

