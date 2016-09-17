/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package cmd

import (
	"fmt"

	"admiral/resourcePools"

	"github.com/spf13/cobra"
)

func init() {
	ResourcePoolsRootCmd.AddCommand(rpRemoveCmd)
}

var rpRemoveCmd = &cobra.Command{
	Use: "rm [RESOURCE-POOL-ID]",

	Short: "Removes existing resource pool.",

	Long: "Removes existing resource pool",

	Run: func(cmd *cobra.Command, args []string) {
		var (
			err   error
			newID string
			id    string
			ok    bool
		)

		if id, ok = ValidateArgsCount(args); !ok {
			fmt.Println("Enter resource pool ID.")
			return
		}
		newID, err = resourcePools.RemoveRPID(id)

		if err == nil {
			fmt.Println("Resource pool removed: " + newID)
		} else if err != nil {
			fmt.Println(err)
		}
	},
}
