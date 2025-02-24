#!/usr/bin/env bash

# Copyright (c) zhou.weiguo(zhouwg2000@gmail.com). 2015-2023. All rights reserved.

# Copyright (c) Project KanTV. 2021-2023. All rights reserved.

# Copyright (c) 2024- KanTV Authors. All Rights Reserved.

if [ "x${PROJECT_ROOT_PATH}" == "x" ]; then
    echo "pwd is `pwd`"
    echo "pls run . build/envsetup in project's toplevel directory firstly"
    exit 1
fi

. ${PROJECT_ROOT_PATH}/build/public.sh || (echo "can't find public.sh"; exit 1)

show_pwd

if [ "x${BUILD_TARGET}" != "xlinux" ]; then
    echo "pwd is `pwd`"
    echo "pls set export BUILD_TARGET=linux in project's toplevel build/envsetup.sh firstly"
    exit 1
fi


export PKG_CONFIG_PATH=${FF_PREFIX}/lib/pkgconfig:$PKG_CONFIG_PATH
echo "export PKG_CONFIG_PATH=${FF_PREFIX}/lib/pkgconfig:$PKG_CONFIG_PATH"

#./configure  --prefix=${FF_PREFIX} --disable-shared --enable-static --enable-ffmpeg --enable-ffplay --enable-ffprobe --enable-avfilter --enable-postproc --enable-rpath --disable-doc --disable-htmlpages --disable-manpages --disable-podpages --disable-txtpages --enable-libx264 --enable-libx265 --enable-libaom --enable-libwebp --enable-libsvtav1 --enable-libvvdec --enable-libvvenc --enable-libfreetype --enable-libfribidi --enable-libharfbuzz --enable-gpl --enable-encoders --enable-pic --enable-rpath --extra-cflags="-I${FF_PREFIX}/include -I${FF_PREFIX}/include/freetype2  -I${FF_PREFIX}/include/fribidi -I${FF_PREFIX}/include/harfbuzz -I./" --extra-ldflags="-L${FF_PREFIX}/lib/ -lx264 -lx265 -laom -lwebp -lsharpyuv -lavformat -lavcodec -lSvtAv1Dec -lSvtAv1Enc -lvvenc -lvvdec -lswscale -lavutil -lswresample -lpthread -ldl -lz  -lxcb -lasound -lSDL2 -lXv -lX11 -lXext -pthread -lm -latomic -lm -lz -pthread -lz -lm -lXfixes -lXinerama -lSDL2 -lstdc++" --pkg-config="/usr/bin/pkg-config"
./configure  --prefix=${FF_PREFIX} --disable-shared --enable-static --enable-ffmpeg --enable-ffplay --enable-ffprobe --enable-avfilter --enable-postproc --enable-rpath --disable-doc --disable-htmlpages --disable-manpages --disable-podpages --disable-txtpages --enable-libx264 --enable-libx265 --enable-libaom --enable-libwebp --enable-libsvtav1 --enable-libvvdec --enable-libvvenc --enable-libfreetype --enable-libfribidi --enable-libharfbuzz --enable-gpl --enable-encoders --enable-pic --enable-rpath --extra-cflags="-I${FF_PREFIX}/include -I${FF_PREFIX}/include/freetype2  -I${FF_PREFIX}/include/fribidi -I${FF_PREFIX}/include/harfbuzz -I./" --pkg-config="/usr/bin/pkg-config"

make -j${HOST_CPU_COUNTS}
make install
