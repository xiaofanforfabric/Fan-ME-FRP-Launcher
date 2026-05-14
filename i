package main

/*
#include <stdlib.h>
*/
import "C"
import "unsafe"

//export Hello
func Hello(name *C.char) *C.char {
	return C.CString("Hello " + C.GoString(name))
}

//export FreeString
func FreeString(s *C.char) {
	C.free(unsafe.Pointer(s))
}

func main() {}
