PRJ_DIR=/home/escou64/herd-ware/root/public

git checkout public
git pull origin public
git submodule update

cd ${PRJ_DIR}/hw/common
git checkout public
git pull origin public

cd ${PRJ_DIR}/hw/core/aubrac
git checkout public
git pull origin public

cd ${PRJ_DIR}/hw/core/abondance
git checkout public
git pull origin public

cd ${PRJ_DIR}/hw/mem/hay
git checkout public
git pull origin public

cd ${PRJ_DIR}/hw/io
git checkout public
git pull origin public

cd ${PRJ_DIR}/hw/pltf/cheese
git checkout public
git pull origin public

cd ${PRJ_DIR}/sw/isa-tests
git checkout public
git pull origin public

cd ${PRJ_DIR}/tools
git checkout public
git pull origin public
