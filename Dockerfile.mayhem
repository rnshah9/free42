FROM --platform=linux/amd64 ubuntu:22.04 as builder

RUN apt-get update
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y build-essential

COPY . /repo
WORKDIR /repo/common
RUN g++ -o keymap2cc keymap2cc.cc

FROM ubuntu:22.04 as package
COPY --from=builder /repo/common/keymap2cc /
