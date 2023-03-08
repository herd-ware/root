PRJ_DIR=${HERDWARE_ROOT_PATH}

git checkout main
git pull origin main
git submodule update

cd ${PRJ_DIR}/hw/common
git checkout main
git pull origin main

cd ${PRJ_DIR}/hw/core/aubrac
git checkout main
git pull origin main

cd ${PRJ_DIR}/hw/core/abondance
git checkout main
git pull origin main

cd ${PRJ_DIR}/hw/mem/hay
git checkout main
git pull origin main

cd ${PRJ_DIR}/hw/io
git checkout main
git pull origin main

cd ${PRJ_DIR}/hw/pltf/cheese
git checkout main
git pull origin main

cd ${PRJ_DIR}/sw/isa-tests
git checkout main
git pull origin main

cd ${PRJ_DIR}/tools
git checkout main
git pull origin main
