CSS=build/css/site.css
IDX=build/index.html
APP=build/js/app.js
IMG=build/img
SFX=build/sfx
ME=$(shell basename $(shell pwd))

all: $(APP) $(CSS) $(IDX) $(IMG) $(SFX)

build:
	mkdir -p build

$(CSS): resources/public/css/site.css
	mkdir -p build/css
	cp $< $@

$(APP): src/**/** project.clj
	rm -f $(APP)
	lein cljsbuild once min

$(IDX): src/clj/*/handler.clj src/clj/*/utils.clj
	lein run -m $(ME).utils/index-html > $(IDX)

$(SFX): resources/public/sfx/**
	rsync -az resources/public/sfx/ build/sfx
	touch build/sfx

$(IMG): resources/public/img/**
	rsync -az resources/public/img/ build/img
	touch build/img

clean:
	lein clean
	rm -rf $(CSS) $(APP) $(IDX) $(IMG) $(SFX) build/css build/js
