import pytest
from core.import_edit_runtime import _disk_bytes


@pytest.mark.parametrize('value', [1073741824, '1073741824', '8589934592'])
def test_java_long_capacity_is_canonical_and_bounded(value):
    assert _disk_bytes(value) == int(value)


@pytest.mark.parametrize('value', [None, True, 1.5, '01', '+16777216', '16777216.0', '1e9', '8589938688', 16777215])
def test_capacity_cannot_bypass_platform_limit(value):
    with pytest.raises(ValueError, match='IMPORT_EDIT_DISK_LIMIT_INVALID'):
        _disk_bytes(value)
