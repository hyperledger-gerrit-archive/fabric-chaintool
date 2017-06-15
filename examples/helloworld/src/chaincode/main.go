/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

package main

import (
	"fmt"

	"hyperledger/cci/appinit"
	"hyperledger/ccs"

	"org/hyperledger/chaincode/helloworld"

	"github.com/hyperledger/fabric/core/chaincode/shim"
)

type ChaincodeExample struct {
}

func (t *ChaincodeExample) Init(stub shim.ChaincodeStubInterface, param *appinit.Init) error {

	return nil
}

func main() {
	self := &ChaincodeExample{}
	hello := &helloworld.Interface{}
	interfaces := ccs.Interfaces{
		"org.hyperledger.chaincode.helloworld": hello,
		"appinit": self,
	}

	err := ccs.Start(interfaces) // Our one instance implements both Transactions and Queries interfaces
	if err != nil {
		fmt.Printf("Error starting example chaincode: %s", err)
	}
}
