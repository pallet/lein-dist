# lein-dist

Package a [Leiningen](https://github.com/technomancy/leiningen) project into a
self contained tar file, containing all the project's dependencies.

Requires leiningen version 2.

## Usage

    $ lein dist

Creates `myproject-version.tar` in the `target` directory.

## Install

Add `[lein-dist "0.1.0-SNAPSHOT"]` to the `:plugins` vector of your `:user`
profile, or your `project.clj`.

## License

Copyright © 2012 Hugo Duncan.

Distributed under the
[Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html).
