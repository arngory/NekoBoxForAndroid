#!/bin/bash
set -e

source ../buildScript/lib/core/get_source_env.sh

chmod -R 777 .build 2>/dev/null || true
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Install gomobile, pinned so that bindings are reproducible
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ]; then
    rm -rf gomobile
    git clone --no-checkout https://github.com/MatsuriDayo/gomobile.git
    pushd gomobile
    git checkout "$COMMIT_GOMOBILE"
    pushd cmd
    pushd gomobile
    go install -v
    popd
    pushd gobind
    go install -v
    popd
    popd
    popd
    rm -rf gomobile
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

GOBIND="$GOPATH/bin/gobind-matsuri" "$GOPATH/bin/gomobile-matsuri" init
