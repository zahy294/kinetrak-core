try:
    import numpy as np
    aliases = {
        'float_': np.float64,
        'complex_': np.complex128,
        'int_': np.int64,
        'bool_': np.bool_,
        'object_': object,
        'str_': np.str_,
        'unicode_': np.str_,
        'bytes_': np.bytes_,
        'string_': np.bytes_,
        'longlong': np.longlong,
        'ulonglong': np.ulonglong,
        'uint': np.uint,
        'int': int,
        'float': float,
        'bool': bool,
        'cfloat': np.complex64,
        'cdouble': np.complex128,
        'clongdouble': np.clongdouble,
        'single': np.float32,
        'double': np.float64,
        'longdouble': np.longdouble,
    }
    for k, v in aliases.items():
        if not hasattr(np, k):
            setattr(np, k, v)
        if hasattr(np, '_core') and hasattr(np._core, 'numerictypes'):
            if not hasattr(np._core.numerictypes, k):
                setattr(np._core.numerictypes, k, v)
        if hasattr(np, 'core') and hasattr(np.core, 'numerictypes'):
            if not hasattr(np.core.numerictypes, k):
                setattr(np.core.numerictypes, k, v)
except Exception:
    pass
