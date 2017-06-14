package helloworld

import (
	"fmt"

	"github.com/hyperledger/fabric/core/chaincode/shim"
)

type Interface struct {
}

func (t *Interface) Hello(stub shim.ChaincodeStubInterface, name *string) (string, error) {

	return fmt.Sprintf("Hello, %s", *name), nil
}
