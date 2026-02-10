if [ ! -d build/stubs ]; then
  echo "ERROR: build/stubs directory does not exist. Please run 'jreleaser assemble' first."
  exit 1
fi

rm -rf build/jpaxa
mkdir -p build/jpaxa
jbang wrapper install -d build/jpaxa
chmod +x build/jpaxa/jbang || true
jbang export fatjar -O build/jpaxa/jpaxa.jar --files=stubs/=build/stubs jpaxa.java
jbang build/jpaxa/jpaxa.jar build --variants all build/jpaxa -- "{{app}}/jbang" "{{app}}/jpaxa.jar"