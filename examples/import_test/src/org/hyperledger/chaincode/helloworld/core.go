package helloworld

import "github.com/hyperledger/fabric/core/chaincode/shim"

type Interface struct {
}

func (t *Interface) Hello(stub shim.ChaincodeStubInterface, param *string) (string, error) {

	return "Hello, " * param, nil
}
