Drop any exported container image here to bundle it into the APK.

Accepted files: *.tzst (zstd, default) or *.txz (xz) — the same format produced by
"Export as Image" on a container in the Containers screen. Any filename works; every
matching file in this folder is picked up automatically at build time (ContainerManager.
listBundledContainerAssets()).

At runtime:
- First launch with no containers: the first bundled image (alphabetically) is imported
  automatically instead of creating a blank default container.
- Anytime after that: Containers screen > menu > "Import bundled container" lets the user
  (re)import any bundled image, with a picker if more than one is present.

Remove this README.txt (or ignore it — it doesn't end in .tzst/.txz so it's never picked
up as a container) once real image(s) are added here.
