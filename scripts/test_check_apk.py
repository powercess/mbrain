import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("apk_check", pathlib.Path(__file__).with_name("check-apk.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ApkCheckTest(unittest.TestCase):
    def check(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in entries:
                    archive.writestr(name, b"fixture")
            checker.check_apk(apk)

    def test_all_abis(self):
        self.check([f"lib/{abi}/libexample.so" for abi in checker.SUPPORTED_ABIS])

    def test_missing_abi(self):
        with self.assertRaises(ValueError):
            self.check(["lib/arm64-v8a/libexample.so"])

    def test_missing_library_on_one_abi(self):
        with self.assertRaises(ValueError):
            self.check([f"lib/{abi}/libexample.so" for abi in checker.SUPPORTED_ABIS] + ["lib/x86_64/libextra.so"])

    def test_unknown_abi(self):
        with self.assertRaises(ValueError):
            self.check(["lib/unknown/libexample.so"])

    def test_no_native_libraries(self):
        self.check(["classes.dex"])
