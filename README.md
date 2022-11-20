# fs2-io_uring

A library implementing [FS2 I/O APIs](https://fs2.io/#/io) for [Scala Native](https://scala-native.org/) via the [io_uring](https://en.wikipedia.org/wiki/Io_uring) Linux kernel system call interface. The provided implementations are drop-in replacements that can be used to power [http4s Ember](https://http4s.org/v0.23/docs/integrations.html#ember), [Skunk](https://github.com/tpolecat/skunk), and [Rediculous](https://github.com/davenverse/rediculous).

At its heart fs2-io_uring is an [I/O-integrated runtime](https://github.com/typelevel/cats-effect/discussions/3070) for [Cats Effect](https://typelevel.org/cats-effect/). The library is unique in how close to the bare-metal it is and thus how deeply it integrates with kernel I/O APIs. The implementation is literally Cats Effect sharing memory with and talking directly to the kernel: no JDK, no JNI, no overhead. Nearly all system calls are asynchronous, cancelable, and efficiently submitted in batches via the io_uring API. Even performing cancelation is async and fully-backpressured.

## Usage

```scala
libraryDependencies += "com.armanbilge" %%% "fs2-io_uring" % "version"
```

You must also install [liburing](https://github.com/axboe/liburing). For performance, I strongly recommend using static linking, for example:

```scala
nativeConfig ~= { c =>
  c.withCompileOptions(c.compileOptions :+ "-I/home/linuxbrew/.linuxbrew/include")
    .withLinkingOptions(c.linkingOptions :+ "/home/linuxbrew/.linuxbrew/lib/liburing.a")
}
```

To use fs2-io_uring in an application, you should replace `IOApp` with `UringApp`. For tests, you should override the runtime:

```scala
override def munitIORuntime = UringRuntime.global
```

Finally, you can import from `fs2.io.uring.implicits._` to get an implicit io_uring-backed `Network` into scope. You may also directly construct instances of:

- `UringNetwork`
- `UringSocketGroup`
- `UringUnixSockets`

Future releases will add support for datagram sockets and asynchronous (!) file I/O.
