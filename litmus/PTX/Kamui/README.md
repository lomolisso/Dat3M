# Kamui litmus tests

The PTX litmus tests of the kamui-isa project (https://github.com/lomolisso/kamui-isa, `tests/litmus/ptx/`),
copied without their comments by `tools/dat3m/strip_tests.py` there. Do not edit them here.

- `kamui/`: the project's own tests (generated families, hand-written tests, PTX 8.7 feature tests).
- `mixedproxy/`: tests translated from NVlabs/mixedproxy (BSD 3-Clause, `mixedproxy/LICENSE`).

The dialect (cluster placement, state spaces, mbarrier objects, granules and the other PTX 8.x forms) is described
in the kamui-isa repository, `tests/litmus/README.md`, section "The PTX 8.7 dialect".
