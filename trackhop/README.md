# Playlist sync

Vivi's "Playlist sync" settings section is a modified version of
[Trackhop](https://github.com/xlollx/Trackhop) by xlollx, commit `5f2e810`, version 1.15.1.
It is licensed under the AGPL-3.0 with the additional terms in [NOTICE](NOTICE); see [LICENSE](LICENSE).

Modified by pwpp08 for Vivi, November 2026:

- Built as Gradle libraries inside Vivi and opened from its settings, with no launcher icon or Application class.
- Uses Vivi's theme and language; the theme, dynamic colour and language settings are removed.
- Removed: Ko-fi and sponsor links, the GitHub, website and privacy links, the update check, "Trackhop in numbers",
  the playlist generator, the Roast, AI settings and AI covers, the Help and About sections, What's New and the error-report link.
- Uses its own name and icon, and every resource name is prefixed with `th_` to avoid clashes with Vivi's.
