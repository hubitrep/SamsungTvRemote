#!/usr/bin/env python3
"""Flatten the src/ library + driver-core sources into the single-file driver.

Reproduces Hubitat's native #include bundle format: the driver core with its
active `#include davegut.X` lines removed, followed by each included library
wrapped in `// ~~~~~ start/end include ~~~~~` delimiters, every library line
annotated with a `// library marker davegut.X, line N` line-map comment so
runtime error line numbers map back to the library source.

Usage:  python3 flatten.py   ->   writes SamsungTVRemote.groovy
"""
import os, re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src")
CORE = "davegut.SamsungTVRemote.groovy"
OUT = os.path.join(HERE, "SamsungTVRemote.groovy")


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


if __name__ == "__main__":
    flatten()
