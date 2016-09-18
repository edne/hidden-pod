# Hidden Pod
Static hidden services with no effort

![logo](https://i.imgur.com/aF0TFjx.png)


A simple application to serve static content (simple HTML websites,
images, videos, and whatever file you want) hosted directly on your PC
and publishing it on a *.onion* address, accessible only through the
[Tor network](https://www.torproject.org/about/overview.html.en).


## Installation
- Make sure to have `tor` executable in your PATH (it will not be necessary in
  the next releases)
  - On Linux just install Tor from the package manager
  - On Mac you can [install it through
    Macports](https://www.torproject.org/docs/tor-doc-osx.html.en)
  - On Windows download the [Tor Browser Bundle](https://www.torproject.org/projects/torbrowser.html.en)
    and [add to the PATH variable](https://stackoverflow.com/questions/9546324/adding-directory-to-path-environment-variable-in-windows)
    the folder containing the `tor.exe` file

- [Download](https://github.com/edne/hidden-pod/releases) the latest
  release and extract the `.jar` file


## Usage

    $ java -jar hidden-pod-0.1.0-standalone.jar "folder to serve"

It will print something like:

   Serving at: t4xuexgzra2rizfk.onion

Then you can share this URL (better via [something end-to-end
encrypted](https://whispersystems.org/)).

When you close the application or turn off the computer the site will
go down.


## Warnings
This is an alpha software, do not use it for anything critical.


## TODO
- Search for Tor binaries in default paths (or directly download the
  Tor Browser Bundle)
- Directory indexing
- GUI (chose-folder dialog and maybe a tray-icon)

Contributions are welcome!