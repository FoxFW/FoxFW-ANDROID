# Host test harness for the native Pandora port (WSL gcc). Build: make ; run: make test
CC      ?= gcc
CFLAGS  ?= -O1 -g -Wall -Wextra -Wno-unused-parameter -DPIC18_HOST
PICSRC  := ../pic18_core.c ../ihex.c ../pic_emu.c

BINS := test_pic_core test_cracker

all: $(BINS)

test_pic_core: test_pic_core.c $(PICSRC)
	$(CC) $(CFLAGS) -o $@ $^

test: $(BINS)
	@for b in $(BINS); do echo "== $$b =="; ./$$b || exit 1; done

clean:
	rm -f $(BINS)

.PHONY: all test clean

test_cracker: test_cracker.c ../cracker.c $(PICSRC)
	$(CC) $(CFLAGS) -o $@ $^
