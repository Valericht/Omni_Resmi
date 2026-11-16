OmniVision v0.3 - implementasi prd.md v1.1 (M1 penuh termasuk FR11; M2-M4 versi awal, perlu uji lapangan).
Buka folder ini di Android Studio -> Sync -> Run di HP (USB debugging aktif), atau push ke GitHub (workflow Build APK).

MODE (bilah atas)
  MANUAL   joystick kiri = VX/VY, joystick kanan = putar (W). Firmware menjaga heading lock.
  ASSIST   MANUAL + halangan lapis 2 (SLOW/STOP dari perubahan frame zona depan; tombol CAPS mematikannya).
  AUTO     "Misi TR penuh" (FR5: GOTO_STORAGE > PICK > GOTO_TRANSFER > PARK, maks 3 blok, 3 menit) atau "Ke target saja".
           PICK dan PARK menunggu tombol KONFIRMASI (protokol gripper belum ada).
  TRAINING kalibrasi HSV (FR10), robot tidak digerakkan.
STOP: ketuk = STOP dan tahan (STOP terus dikirim); tahan lama = lepas. STOP fisik di firmware tetap wajib (§12.2).

TRAINING (urutan kerja)
  1. Pilih tim (MERAH/BIRU), pilih kelas. 2. SAMPLING ON, ketuk objek di layar (boleh banyak tempat, tiap ketukan menambah rentang).
  3. Geser slider H/S/V untuk menyetel rentang terakhir; mask hijau tampil realtime. 4. UJI DETEKSI ON untuk melihat semua kelas.
  5. Letakkan robot di posisi parkir yang diinginkan, SET AREA TIBA (menyimpan luas objek saat itu sebagai batas "tiba").
  6. SIMPAN PROFIL (per tim, otomatis dimuat saat aplikasi dibuka).
BLOK (FR2): kalibrasi kelas "Blok merah" / "Blok biru" (satu kelas per warna, Earth dan Sky sama-sama muncul di situ). Klasifikasi otomatis:
  Earth vs Sky dari rasio luas antar blob sewarna yang tepi bawahnya sejajar (Sky ~ 0,33 luas Earth, §2). Blob tunggal dianggap Earth (Profile.soloBlock).
  Satu blob = satu kelas: zona (START/TRANSFER/GROUND) yang tertutup blok dibuang. Label di layar: EARTH_RED, EARTH_BLUE, SKY_RED, SKY_BLUE.
LANDMARK (FR11, Training): isi nama, pilih kelas, CORET ON, lingkari area di layar -> region di-bound otomatis, HSV diambil dari semua piksel
  di dalamnya (ikut menambah rentang kelas) dan coretan disimpan sebagai sampel landmark. WAJIB >= 2 posisi robot: geser robot >= 5 cm atau putar >= 10 derajat
  (dicek dari telemetri bila ada), lalu coret lagi dengan nama sama. Landmark valid muncul di daftar Target AUTO ("Landmark: <nama>") dan, bila deteksi
  menempati region tersimpan (irisan >= 30%), tampil dengan nama + coretan aslinya dan didahulukan oleh navigasi/misi (mis. GOTO_TRANSFER).
Warna Earth/Sky Block, Mustika, Robot lawan: "per Appendix, belum tersedia" -> tanpa nilai bawaan, wajib dikalibrasi. Warna landmark lain: titik awal dari RGB §14 (hasil konversi ke HSV OpenCV), kalibrasi tetap dianjurkan.

PROTOKOL (FR6, USB 460800)
  HP > firmware : VX:%f,VY:%f,W:%f | STOP (20 Hz, failsafe firmware 500 ms) | MODE:MANUAL|ASSIST|AUTO | TARGET:<STORAGE|TRANSFER|SPOT|BLOCK> | CAPS:ON|OFF (diulang tiap 500 ms)
  firmware > HP : {"pose":[x,y,yaw],"us":[a,b,c],"spd":..,"safety":..,"batt":..,"pl":0|1} 20 Hz selama HP aktif; us 999 = tak terbaca.
  Satuan VX/VY/W = RPM roda (-500..500): VX maju, VY geser kanan, W + = CCW. Tanpa telemetri, aplikasi tetap jalan (mode legacy).
Firmware: omni_patched/omni/omni3x (serial_cmd.*, webui.cpp). Tes: host_test/run_host_tests.sh.

Angka awal gerak/halangan ada di Tuning.kt dan ikut tersimpan di profil (KP, FORWARD_RPM, ambang halangan).
