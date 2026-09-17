"""Fail CI if a universal APK loses native library coverage for a supported ABI."""
import sys
import zipfile

SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}


def check_apk(path):
    with zipfile.ZipFile(path) as apk:
        if apk.testzip() is not None:
            raise ValueError("APK contains a corrupt ZIP entry")
        libraries = {abi: set() for abi in SUPPORTED_ABIS}
        for name in apk.namelist():
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == "lib" and name.endswith(".so"):
                abi, library = parts[1:]
                if abi not in libraries:
                    raise ValueError(f"Unreviewed ABI: {abi}")
                libraries[abi].add(library)
        expected = set().union(*libraries.values())
        # An APK without native libraries is ABI-independent.
        for abi, present in sorted(libraries.items()):
            missing = expected - present
            if missing:
                raise ValueError(f"{abi} is missing native libraries: {', '.join(sorted(missing))}")
        print(f"APK native library coverage verified: {', '.join(sorted(SUPPORTED_ABIS))}")
        print(f"Native libraries per ABI: {len(expected)}; device runtime testing is still required.")


if __name__ == "__main__":
    check_apk(sys.argv[1])
