import 'dart:io';
import 'dart:convert';

void main() async {
  String raw;
  try {
    raw = await stdin.transform(utf8.decoder).join();
  } catch (e) {
    print(jsonEncode({'output': null, 'error': 'INTERNAL_ERROR: failed to read stdin: $e', 'timeMs': 0.0, 'memoryMb': 0.0}));
    exit(1);
  }

  Map<String, dynamic> payload;
  try {
    payload = jsonDecode(raw) as Map<String, dynamic>;
  } catch (e) {
    print(jsonEncode({'output': null, 'error': 'INTERNAL_ERROR: failed to parse input: $e', 'timeMs': 0.0, 'memoryMb': 0.0}));
    exit(1);
  }

  final code = payload['code'] as String? ?? '';
  final args = (payload['args'] as List<dynamic>?) ?? [];

  final tmpDir = await Directory('/tmp').createTemp('judge_');
  final sourceFile = File('${tmpDir.path}/solution.dart');
  final resultFile = File('${tmpDir.path}/result.txt');

  final argsLiteral = args.map((a) {
    if (a is String) return '"${a.replaceAll(r'\', r'\\').replaceAll('"', r'\"')}"';
    return a.toString();
  }).join(', ');

  // Escape single quotes in the result file path for embedding in Dart string literal
  final safeResultPath = resultFile.path.replaceAll(r'\', r'\\').replaceAll("'", r"\'");

  // Generate solution.dart: user code + main() that writes result to a file
  // Uses string concatenation to avoid Dart template interpolation conflicts
  await sourceFile.writeAsString(
    "import 'dart:io';\n\n" +
    code + "\n\n" +
    "void main() {\n" +
    "  final resultFile = File('$safeResultPath');\n" +
    "  final sw = Stopwatch()..start();\n" +
    "  try {\n" +
    "    final result = solution($argsLiteral);\n" +
    "    sw.stop();\n" +
    "    resultFile.writeAsStringSync('OK\\n' + result.toString() + '\\n' + (sw.elapsedMicroseconds / 1000.0).toString());\n" +
    "  } catch (e) {\n" +
    "    sw.stop();\n" +
    "    resultFile.writeAsStringSync('RUNTIME_ERROR\\n' + e.toString() + '\\n' + (sw.elapsedMicroseconds / 1000.0).toString());\n" +
    "  }\n" +
    "}\n"
  );

  // Run with `dart run` (JIT — no native binary written, safe with noexec tmpfs)
  final result = await Process.run('dart', ['run', sourceFile.path]);

  String? errorMsg;
  String outputVal = '';
  double timeMs = 0.0;

  if (resultFile.existsSync()) {
    final lines = resultFile.readAsLinesSync();
    final status = lines.isNotEmpty ? lines[0] : 'RUNTIME_ERROR';
    final value = lines.length > 1 ? lines[1] : '';
    timeMs = lines.length > 2 ? double.tryParse(lines[2]) ?? 0.0 : 0.0;

    if (status == 'OK') {
      outputVal = value;
    } else {
      errorMsg = '$status: $value';
    }
  } else {
    // result file not written → compile/syntax error
    final stderr = (result.stderr as String).replaceAll('\n', ' ').trim();
    errorMsg = 'COMPILE_ERROR: $stderr';
  }

  await tmpDir.delete(recursive: true);

  if (errorMsg != null) {
    print(jsonEncode({'output': null, 'error': errorMsg, 'timeMs': timeMs, 'memoryMb': 0.0}));
  } else {
    print(jsonEncode({'output': outputVal, 'error': null, 'timeMs': timeMs, 'memoryMb': 0.0}));
  }
}
