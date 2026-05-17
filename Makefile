JAVAC ?= javac
JAVA ?= java
OUT ?= out
SOURCES := $(shell find src/main/java -name '*.java')

.PHONY: build clean

build:
	$(JAVAC) -d $(OUT) $(SOURCES)

clean:
	rm -rf $(OUT)
