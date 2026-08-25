from functools import total_ordering


@total_ordering
class StrictVersion:
    def __init__(self, version):
        self.version = tuple(int(part) for part in version.split('.'))

    def __str__(self):
        return '.'.join(str(part) for part in self.version)

    def __repr__(self):
        return "StrictVersion('%s')" % self

    def _coerce(self, other):
        if isinstance(other, StrictVersion):
            return other.version
        return StrictVersion(str(other)).version

    def __eq__(self, other):
        return self.version == self._coerce(other)

    def __lt__(self, other):
        return self.version < self._coerce(other)
