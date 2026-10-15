#!/usr/bin/env python3
"""Flatten the src/ library + driver-core sources into the single-file driver.

Reproduces Hubitat's native #include bundle format: the driver core with its
active `#include davegut.X` lines removed, followed by each included library
wrapped in `// ~~~~~ start/end include ~~~~~` delimiters, every library line
annotated with a `// library marker davegut.X, line N` line-map comment so
runtime error line numbers map back to the library source.

Also rebuilds SamsungTVRemote.zip, the Hubitat bundle of the same src/ files.

Usage:  python3 flatten.py   ->   writes SamsungTVRemote.groovy and SamsungTVRemote.zip
"""
import os, re, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src")
CORE = "davegut.SamsungTVRemote.groovy"
OUT = os.path.join(HERE, "SamsungTVRemote.groovy")
BUNDLE = os.path.join(HERE, "SamsungTVRemote.zip")
BUNDLE_NAME = "SamsungTVRemote_4_1"


def flatten():
    core = open(os.path.join(SRC, CORE)).read().splitlines()
    # include order = the active `#include davegut.X` directives, in file order
    order = [m.group(1) for ln in core
             if (m := re.match(r'#include davegut\.(\S+)\s*$', ln))]
    out = [ln for ln in core if not re.match(r'#include davegut\.\S+\s*$', ln)]
    for i, name in enumerate(order, start=1):
        lib = open(os.path.join(SRC, f"davegut.{name}.groovy")).read().splitlines()
        out.append(f"// ~~~~~ start include ({i}) davegut.{name} ~~~~~")
        for n, ll in enumerate(lib, start=1):
            out.append(f"{ll} // library marker davegut.{name}, line {n}")
        out.append(f"// ~~~~~ end include ({i}) davegut.{name} ~~~~~")
    open(OUT, "w").write("\n".join(out) + "\n")
    print(f"wrote {OUT} ({len(out)} lines; libraries: {', '.join(order)})")
    bundle()


def bundle():
    """install.txt/update.txt list the namespace, bundle name, then each file by type."""
    libs = [f"davegut.{n}.groovy" for n in ("Logging", "samsungTvApps", "samsungTvWebsocket",
            "samsungTvST", "SmartThingsInterface", "samsungTvPresets")]
    listing = "\n".join(["davegut", BUNDLE_NAME] + [f"library {f}" for f in libs]
                        + [f"driver {CORE}"])
    with zipfile.ZipFile(BUNDLE, "w", zipfile.ZIP_DEFLATED) as z:
        for f in libs + [CORE]:
            z.write(os.path.join(SRC, f), f)
        z.writestr("install.txt", listing)
        z.writestr("update.txt", listing)
    print(f"wrote {BUNDLE} ({len(libs) + 1} files)")


if __name__ == "__main__":
    flatten()
