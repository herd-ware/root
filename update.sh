PRJ_DIR=/home/escou64/herd-ware/root/main

git checkout main
git pull origin main
git submodule update

cd ${PRJ_DIR}/hw/common
git checkout main
git pull origin main

cd ${PRJ_DIR}/hw/core/aubrac
git checkout main
git pull origin main

cd ${PRJ_DIR}/hw/core/salers
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

cd ${PRJ_DIR}/sw/lib-herd
git checkout main
git pull origin main

cd ${PRJ_DIR}/sw/bare
git checkout main
git pull origin main

cd ${PRJ_DIR}/tools
git checkout main
git pull origin main
