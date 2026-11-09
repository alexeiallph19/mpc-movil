# Avisos de terceros

MPM Player incorpora componentes de interfaz derivados del proyecto VLC for Android de VideoLAN.

- Proyecto original: https://github.com/videolan/vlc-android
- Revisión consultada: 57fe6cf2fa308a9a20f8945a71b1a581cfff942d
- Licencia de los componentes VLC copiados/adaptados: GNU GPL versión 2 o, a elección, cualquier versión posterior.
- Licencia completa: `COPYING`.

## Componentes incorporados o adaptados

- `vlc_seekbar_track.xml`: basado en el drawable `po_seekbar_video.xml`.
- `vlc_seekbar_thumb.xml`, `vlc_seekbar_thumb_normal.xml` y `vlc_seekbar_thumb_pressed.xml`: basados en los drawables de thumb de VLC.
- `VlcAccessibleSeekBar.kt`: adaptación independiente de la lógica de accesibilidad de `AccessibleSeekBar.kt`.

Los archivos adaptados incluyen avisos de atribución. Los cambios específicos de MPM Player están identificados en el código.

MPM Player se distribuye bajo GNU GPL v2 o cualquier versión posterior en lo que respecta a la obra derivada que incorpora estos componentes. Las dependencias de terceros conservan sus respectivas licencias. En particular, LibVLC mantiene la licencia indicada por VideoLAN para esa biblioteca.

La interfaz y la integración en MPM Player han sido adaptadas; MPM Player no es una versión oficial de VLC ni está afiliado a VideoLAN.
