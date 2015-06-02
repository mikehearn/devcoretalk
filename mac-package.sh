#!/usr/bin/env bash

jh=`/usr/libexec/java_home`

$jh/bin/javapackager -deploy \
    -BappVersion=1 \
    -Bmac.CFBundleIdentifier=net.plan99.timestamper \
    -Bmac.CFBundleName=Timestamper \
    -Bicon=mac.icns \
    -Bruntime="$jh/../../" \
    -native dmg \
    -name Timestamper \
    -title Timestamper \
    -vendor Vinumeris \
    -outdir deploy \
    -appclass timestamper.Main \
    -srcfiles target/timestamper-shaded.jar \
    -outfile Timestamper