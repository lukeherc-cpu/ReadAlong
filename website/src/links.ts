export const REPO = 'https://github.com/readalongapp/ReadAlong'
export const RELEASE = `${REPO}/releases/latest`
const file = (name: string) => `${RELEASE}/download/${name}`

export const DOWNLOADS = {
  android: RELEASE,
  windowsMsi: file('ReadAlong-Windows.msi'),
  windowsZip: file('ReadAlong-Windows-portable.zip'),
  linuxFlatpak: file('ReadAlong-Linux.flatpak'),
  linuxTar: file('ReadAlong-Linux-x86_64.tar.gz'),
  macArm: file('ReadAlong-macOS-AppleSilicon.dmg'),
  macIntel: file('ReadAlong-macOS-Intel.dmg'),
}
export const KOFI = 'https://ko-fi.com/readalong'

/** A file under public/media, resolved against whatever base path the site is served from. */
export const media = (name: string) => `${import.meta.env.BASE_URL}media/${name}`
