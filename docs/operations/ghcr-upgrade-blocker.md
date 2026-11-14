# Menunda upgrade produksi ke image GHCR

Status: terbuka, produksi tetap pada image lama.

Ditemukan saat mencoba memindahkan image produksi dari build lokal ke GHCR.

Langkah yang sudah dilakukan:

1. publish workflow diperbaiki (#11): target Docker Hub `artivisi/balaka` dihapus, GHCR saja. Publish main sekarang hijau, image `ghcr.io/indraoverflow/balaka:main-4599cac` terbit.
2. database produksi di-dump, lalu dipulihkan ke database salinan di instance Postgres yang sama.
3. image baru dijalankan terhadap salinan itu, dua kali.

Hasil uji pertama, validasi Flyway aktif:

```
Migration checksum mismatch for migration version 010
Migration checksum mismatch for migration version 011
Migration checksum mismatch for migration version 012
```

Hasil uji kedua, `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`:

```
ERROR: V014: parent account 2.1 (LIABILITY) for 2.1.50 is missing
Caused by: Failed to execute script V014__period_close_checks.sql
```

## Kenapa ini penting

Dua masalah terpisah, keduanya menghalangi upgrade produksi:

- **Checksum V010 sampai V012 berbeda** antara yang tercatat di `flyway_schema_history` produksi dan file di `main`. Artinya isi migrasi itu pernah berubah setelah dijalankan, sehingga Flyway menolak sebelum sempat migrasi.
- **V014 tidak cocok dengan data produksi**: migrasi berasumsi ada akun induk `2.1` untuk akun `2.1.50`, dan data produksi tidak memilikinya. Perbaikan validasi tidak menolong, ini kegagalan SQL pada data nyata.

## Dampak

Produksi masih berjalan pada image lama. Belum ada perubahan yang dilakukan pada database produksi, dan dump pra-percobaan tersimpan.

## Yang dibutuhkan

- tentukan commit yang dipakai membangun image produksi, lalu samakan isi V010 sampai V012 di repo dengan yang tercatat di produksi, atau jalankan `flyway repair` secara sadar dan catat alasannya;
- perbaiki V014 supaya tidak berasumsi akun induk ada, misalnya `INSERT ... WHERE EXISTS` atau backfill induk yang hilang lebih dulu;
- setelah keduanya, ulangi uji pada salinan database sebelum menyentuh produksi.

Catatan kecil: perbaikan Docker Hub yang sama pernah dibuat di commit `0f1f5fdf` lalu muncul lagi, jadi perubahan workflow ini perlu dijaga.


## Bukti pendukung

- dump produksi pra-percobaan: `/home/apps/backups/balaka-pre-ghcr-20261114-1515.dump`
- uji dijalankan terhadap database salinan, bukan produksi, dan salinan itu sudah dihapus
- produksi tidak tersentuh, `/actuator/health` tetap 200 selama pengujian
