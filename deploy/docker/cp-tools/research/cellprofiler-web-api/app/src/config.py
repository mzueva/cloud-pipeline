import os


class Config:
    BATCH_ENABLED = os.getenv('CELLPROFILER_API_BATCH_SPEC_FILE', None)
    RESULTS_DIR = os.getenv('CELLPROFILER_API_BATCH_RESULTS_DIR', None)
    COMMON_RESULTS_DIR = os.getenv('CELLPROFILER_API_COMMON_RESULTS_DIR')
    RAW_IMAGE_DATA_ROOT = os.getenv('CELLPROFILER_API_RAW_DATA_ROOT_DIR')
    POOL_SIZE = os.getenv('CELLPROFILER_API_PROCESSES', 2)
    RUN_DELAY = os.getenv('CELLPROFILER_API_RUN_DELAY', 2)

