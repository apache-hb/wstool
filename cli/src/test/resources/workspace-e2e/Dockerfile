FROM eclipse-temurin:25-jre-noble

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        build-essential \
        bzip2 \
        ca-certificates \
        gawk \
        patch \
        perl \
        python3 \
        texinfo \
        xz-utils \
    && rm -rf /var/lib/apt/lists/*
