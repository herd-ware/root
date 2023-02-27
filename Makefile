PRJ_DIR = `pwd`
ISA_CFG = C32
HW_CFG = O1V000
PLTF_CFG = ${ISA_CFG}${HW_CFG}
CHEESE_CFG = HERD_${ISA_CFG}_CH${HW_CFG}
FPGA_BOARD = arty-a7-35t

BLACK=\033[1;30m
RED=\033[1;31m
GREEN=\033[1;32m
ORANGE=\033[1;33m
PURPLE=\033[1;35m
NOCOLOR=\033[1m


# ******************************
#            CHEESE
# ******************************
cheese-dir: all-dir
	mkdir -p sim/vcd/${CHEESE_CFG}
	mkdir -p sim/dfp/${CHEESE_CFG}
	mkdir -p sim/etd/${CHEESE_CFG}

cheese-sim: cheese-dir
	sbt "test:runMain herd.pltf.cheese.CheeseSim${PLTF_CFG} --target-dir sim/src"
	verilator -Wno-WIDTH -Wno-CMPCONST --trace -cc ${PRJ_DIR}/sim/src/CheeseSim.v ${PRJ_DIR}/sim/src/data.sv --exe --Mdir ${PRJ_DIR}/sim/obj --build ${PRJ_DIR}/hw/pltf/cheese/sim/sim.cpp ${PRJ_DIR}/hw/pltf/cheese/sim/lib/etd.cpp -CFLAGS -DCONFIG_${PLTF_CFG}
	cp ${PRJ_DIR}/sim/obj/VCheeseSim ${PRJ_DIR}/sim/exe/${CHEESE_CFG}
	cp ${PRJ_DIR}/hw/pltf/cheese/sim/isa-tests/${PLTF_CFG}.tst ${PRJ_DIR}/sim/tst/${CHEESE_CFG}.tst
	./tools/isa-tests.sh ${CHEESE_CFG} ${PRJ_DIR}

cheese-sim-all:
	make cheese-sim ISA_CFG=C32 HW_CFG=AU1V000
	make cheese-sim ISA_CFG=C32 HW_CFG=AU1V020
	make cheese-sim ISA_CFG=C32 HW_CFG=AU1V021
	make cheese-sim ISA_CFG=C32 HW_CFG=AB1V000
	make cheese-sim ISA_CFG=C32 HW_CFG=AB1V020
	make cheese-sim ISA_CFG=C32 HW_CFG=AB1V021

cheese-fpga:
	mkdir -p fpga/${FPGA_BOARD}/${CHEESE_CFG}/gen/
	sbt "runMain herd.pltf.cheese.Cheese${ISA_CFG}${HW_CFG} --target-dir output"
	cp output/Cheese.v fpga/${FPGA_BOARD}/${CHEESE_CFG}/gen/${CHEESE_CFG}.v
	cp output/data.sv fpga/${FPGA_BOARD}/${CHEESE_CFG}/gen/
	sed -i 's/module Cheese/module ${CHEESE_CFG}/' fpga/${FPGA_BOARD}/${CHEESE_CFG}/gen/${CHEESE_CFG}.v
	cp sw/bare/hex/boot-32.hex fpga/${FPGA_BOARD}/${CHEESE_CFG}/gen/boot.mem
	cp sw/bare/hex/rom-32.hex fpga/${FPGA_BOARD}/${CHEESE_CFG}/gen/rom.mem

# ******************************
#             ALL
# ******************************
all-dir:
	rm -rf sim/obj
	mkdir -p sim/src
	mkdir -p sim/obj
	mkdir -p sim/exe
	mkdir -p sim/log
	mkdir -p sim/tst

tests-report: 
	@echo "${PURPLE}******************************${NOCOLOR}"
	@echo "${NOCOLOR}Generated test reports: `grep "TEST REPORT" sim/log/* | wc -l` ${NOCOLOR}"
	@echo "${RED}Failed: `grep "TEST REPORT: FAILED" sim/log/* | wc -l` ${NOCOLOR}"
	@echo "${RED}Timeout: `grep "TEST REPORT: TIMEOUT" sim/log/* | wc -l` ${NOCOLOR}"
	@echo "${ORANGE}Wrong infos: `grep "TEST REPORT: WRONG INFOS" sim/log/* | wc -l` ${NOCOLOR}"
	@echo "${GREEN}Success: `grep "TEST REPORT: SUCCESS" sim/log/* | wc -l` ${NOCOLOR}"
	@echo "${PURPLE}******************************${NOCOLOR}"

clean:
	rm -rf output/
	rm -rf target/
	rm -rf sim/
