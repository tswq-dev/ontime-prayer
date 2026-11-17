# Adhan recordings in the app

`app/src/main/res/raw/`, loudness-normalised (−16 LUFS), mono 64 kbps MP3:

| File | Voice | Source file in [Kiwifu/adhan-mp3](https://github.com/Kiwifu/adhan-mp3) |
|---|---|---|
| `adhan_afasy.mp3` | Mishary Alafasy | `Mishary_Al_Afasy_-_HQ_(مشاري_العفاسي).mp3` |
| `adhan_afasy_fajr.mp3` | Mishary Alafasy, Fajr | `Mishary_Rashid_Alafasy_3_-_Fajr_Kuwait_(…).mp3` |
| `adhan_qatami.mp3` | Nasser Al-Qatami | `Nasser_Al_Qatami_-_HQ_(ناصر_القطامي).mp3` |
| `adhan_takbir_afasy.mp3` | Alafasy, opening takbirs (first 20 s) | from `adhan_afasy` |
| `adhan_takbir_qatami.mp3` | Al-Qatami, opening takbirs (first 27.6 s) | from `adhan_qatami` |

The library has no Fajr recording by Al-Qatami, so Fajr uses Alafasy's Fajr adhan for every bundled voice.
The library states no licence; the recordings belong to their owners. Remove a voice if its owner asks.
