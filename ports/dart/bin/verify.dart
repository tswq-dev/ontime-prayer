// Reads data/vectors.csv (plain CSV on stdin), prints one line per mismatch (ds|date|lat|lon|zone|field|want) and "values N".
import 'dart:convert';
import 'dart:io';
import 'package:ontime_prayer/umm_al_qura.dart';

Future<void> main() async {
  const ev = ['fajr', 'sunrise', 'dhuhr', 'asr', 'maghrib', 'isha', 'eid'];
  var n = 0, header = true;
  final out = StringBuffer();
  await for (final line in stdin.transform(utf8.decoder).transform(const LineSplitter())) {
    if (header) { header = false; continue; }
    final f = line.split(',');
    final t = UmmAlQura.prayerTimes(f[1], double.parse(f[2]), double.parse(f[3]), zone: double.parse(f[4]), ramadan: f[5] == '1');
    final got = t == null ? null : [t.fajr, t.sunrise, t.dhuhr, t.asr, t.maghrib, t.isha, t.eid];
    for (var i = 0; i < 7; i++) {
      final v = f[6 + i];
      if (v.isEmpty) continue;
      n++;
      if (got == null || got[i] != v) out.writeln([f[0], f[1], f[2], f[3], f[4], ev[i], v].join('|'));
    }
  }
  stdout.write(out);
  stdout.writeln('values $n');
}
